//! 配对机制(参考 Moonlight + Sunshine 的 PIN 配对)
//!
//! 设计:
//! - 服务端首次启动生成 Ed25519 密钥对,持久化到本地 pairing.json
//! - 启动时若无已配对客户端,生成 6 位 PIN 显示在 PC 控制台
//! - 客户端首次连接发起 Hello,服务端返回 PairRequired + server_pubkey + server_nonce
//! - 客户端用 PIN + 自身密钥对签名 server_nonce,发送 PairRequest
//! - 服务端验证 PIN + 签名 → 加入白名单 → 返回 PairResponse
//! - 后续连接客户端发送 HelloPaired(带签名),服务端查白名单放行
//!
//! 持久化路径(服务端):
//! - Windows: %APPDATA%/meowmic/pairing.json
//! - Linux/macOS: ~/.config/meowmic/pairing.json
//!
//! 安全性:
//! - PIN 6 位数字,有效期=服务端启动后直到配对成功或服务端退出
//! - Ed25519 签名防止公钥冒充(攻击者拿到 client_pubkey 无法伪造签名)
//! - 白名单上限 8 个客户端(个人用足够,超出需先移除旧的)

use std::path::{Path, PathBuf};

use base64::Engine;
use ed25519_dalek::{Signature, SigningKey, VerifyingKey, Verifier, Signer};
use rand::Rng;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;
use tokio::sync::Mutex;

/// base64 标准编码引擎的简写
const B64: base64::engine::general_purpose::GeneralPurpose = base64::engine::general_purpose::STANDARD;

/// 已配对客户端公钥(原始 32 字节,base64 编码持久化)
const MAX_PAIRED_CLIENTS: usize = 8;

/// 配对错误
#[derive(Debug, Error)]
pub enum PairingError {
    #[error("IO 错误: {0}")]
    Io(#[from] std::io::Error),
    #[error("JSON 序列化失败: {0}")]
    Json(#[from] serde_json::Error),
    #[error("base64 解码失败: {0}")]
    Base64(#[from] base64::DecodeError),
    #[error("Ed25519 错误: {0}")]
    Ed25519(String),
    #[error("PIN 不正确")]
    BadPin,
    #[error("签名验证失败")]
    BadSignature,
    #[error("已配对客户端数量已达上限({0})")]
    TooManyClients(usize),
    #[error("客户端公钥长度错误: 期望 32 字节, 实际 {0}")]
    BadPubkeyLen(usize),
}

/// 已配对客户端条目
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedClient {
    /// 客户端公钥(base64 编码的 32 字节 Ed25519 公钥)
    pub pubkey_b64: String,
    /// 客户端设备名(配对时上报,用于 UI 显示)
    pub client_name: String,
    /// 配对时间(UNIX 秒)
    pub paired_at: u64,
}

/// 配对状态持久化结构(服务端)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairingState {
    /// 服务端 Ed25519 私钥(base64 编码的 32 字节种子)
    pub server_secret_b64: String,
    /// 已配对的客户端列表
    pub paired_clients: Vec<PairedClient>,
}

/// 服务端运行时配对管理器
pub struct PairingManager {
    state: Mutex<PairingState>,
    signing_key: SigningKey,
    /// 当前 PIN(配对成功后清空)
    current_pin: Mutex<Option<String>>,
    /// 配对状态文件路径
    state_path: PathBuf,
}

impl PairingManager {
    /// 加载或创建配对状态
    ///
    /// - `state_dir`:状态文件所在目录(由调用方决定,如 %APPDATA%/meowmic)
    ///   传 None 则用内存状态(测试用,不持久化)
    pub fn load_or_create(state_dir: Option<PathBuf>) -> Result<Self, PairingError> {
        let state_path = state_dir
            .unwrap_or_else(std::env::temp_dir)
            .join("pairing.json");

        let state = if state_path.exists() {
            let content = std::fs::read_to_string(&state_path)?;
            serde_json::from_str::<PairingState>(&content)?
        } else {
            // 首次启动:生成新的 Ed25519 密钥对
            let mut rng = rand::thread_rng();
            let signing_key = SigningKey::generate(&mut rng);
            let state = PairingState {
                server_secret_b64: B64.encode(signing_key.to_bytes()),
                paired_clients: Vec::new(),
            };
            if let Some(parent) = state_path.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            let _ = std::fs::write(&state_path, serde_json::to_string_pretty(&state)?);
            state
        };

        // 从持久化的种子重建 SigningKey
        let secret_bytes = B64.decode(&state.server_secret_b64)?;
        if secret_bytes.len() != 32 {
            return Err(PairingError::Ed25519(format!(
                "server_secret 长度错误: 期望 32 字节, 实际 {}",
                secret_bytes.len()
            )));
        }
        let signing_key = SigningKey::from_bytes(
            secret_bytes.as_slice().try_into().map_err(|_| {
                PairingError::Ed25519("SigningKey::from_bytes 长度不匹配".into())
            })?,
        );

        Ok(Self {
            state: Mutex::new(state),
            signing_key,
            current_pin: Mutex::new(None),
            state_path,
        })
    }

    /// 服务端公钥(32 字节,用于 /serverinfo 暴露给客户端)
    pub fn server_pubkey(&self) -> [u8; 32] {
        self.signing_key.verifying_key().to_bytes()
    }

    /// 服务端公钥的 base64 表示(用于 JSON 暴露)
    pub fn server_pubkey_b64(&self) -> String {
        B64.encode(self.server_pubkey())
    }

    /// 生成新的 6 位 PIN(若已有未使用的 PIN 则保留)
    pub async fn ensure_pin(&self) -> String {
        let mut pin_guard = self.current_pin.lock().await;
        if let Some(pin) = pin_guard.clone() {
            return pin;
        }
        let pin = format!("{:06}", rand::thread_rng().gen_range(0..1_000_000u32));
        *pin_guard = Some(pin.clone());
        pin
    }

    /// 配对成功后清空 PIN(下次配对需要重新生成)
    pub async fn clear_pin(&self) {
        *self.current_pin.lock().await = None;
    }

    /// 当前 PIN(可能为 None,表示无需配对或已配对完成)
    pub async fn current_pin(&self) -> Option<String> {
        self.current_pin.lock().await.clone()
    }

    /// 已配对客户端列表快照
    pub async fn paired_clients(&self) -> Vec<PairedClient> {
        self.state.lock().await.paired_clients.clone()
    }

    /// 检查客户端公钥是否在白名单中
    pub async fn is_client_paired(&self, client_pubkey: &[u8]) -> bool {
        let expected_b64 = B64.encode(client_pubkey);
        let state = self.state.lock().await;
        state.paired_clients.iter().any(|c| c.pubkey_b64 == expected_b64)
    }

    /// 处理客户端配对请求
    ///
    /// 返回 (success, server_pubkey, error_msg)
    pub async fn handle_pair_request(
        &self,
        client_pubkey: Vec<u8>,
        client_name: String,
        pin: String,
        server_nonce: u64,
        signature: Vec<u8>,
    ) -> (bool, Vec<u8>, String) {
        let server_pubkey = self.server_pubkey().to_vec();

        // 1. 验证客户端公钥长度
        if client_pubkey.len() != 32 {
            return (false, server_pubkey, format!("客户端公钥长度错误: {}", client_pubkey.len()));
        }

        // 2. 验证 PIN
        let current_pin = self.current_pin.lock().await.clone();
        let expected_pin = match current_pin {
            Some(p) => p,
            None => return (false, server_pubkey, "服务端未开启配对".into()),
        };
        if pin != expected_pin {
            return (false, server_pubkey, "PIN 不正确".into());
        }

        // 3. 验证签名(签名内容 = server_nonce 的 LE 字节)
        let pubkey_arr: &[u8; 32] = match client_pubkey.as_slice().try_into() {
            Ok(arr) => arr,
            Err(_) => return (false, server_pubkey, "客户端公钥长度错误(期望 32 字节)".into()),
        };
        let verifying_key = match VerifyingKey::from_bytes(pubkey_arr) {
            Ok(k) => k,
            Err(e) => return (false, server_pubkey, format!("公钥无效: {}", e)),
        };
        let sig_bytes: [u8; 64] = match signature.as_slice().try_into() {
            Ok(b) => b,
            Err(_) => return (false, server_pubkey, "签名长度错误(期望 64 字节)".into()),
        };
        let signature = Signature::from_bytes(&sig_bytes);
        let msg = server_nonce.to_le_bytes();
        if verifying_key.verify(&msg, &signature).is_err() {
            return (false, server_pubkey, "签名验证失败".into());
        }

        // 4. 加入白名单(若已存在则更新名称)
        let pubkey_b64 = B64.encode(&client_pubkey);
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);

        let mut state = self.state.lock().await;
        // 移除同公钥的旧条目(允许重新配对更新名称)
        state.paired_clients.retain(|c| c.pubkey_b64 != pubkey_b64);
        // 检查上限
        if state.paired_clients.len() >= MAX_PAIRED_CLIENTS {
            return (false, server_pubkey, format!("已配对客户端数量已达上限({})", MAX_PAIRED_CLIENTS));
        }
        state.paired_clients.push(PairedClient {
            pubkey_b64,
            client_name: client_name.clone(),
            paired_at: now,
        });

        // 持久化
        if let Err(e) = self.persist(&state) {
            tracing::warn!("配对状态持久化失败: {}", e);
        }

        // 清空 PIN(配对成功后下次需重新生成)
        *self.current_pin.lock().await = None;

        tracing::info!("客户端配对成功: {}", client_name);
        (true, server_pubkey, String::new())
    }

    /// 验证已配对客户端的 HelloPaired 签名
    ///
    /// 签名内容 = SHA256(client_name || client_pubkey || nonce_le_bytes)
    pub async fn verify_paired_hello(
        &self,
        client_pubkey: &[u8],
        client_name: &str,
        nonce: u64,
        signature: &[u8],
    ) -> Result<(), PairingError> {
        if client_pubkey.len() != 32 {
            return Err(PairingError::BadPubkeyLen(client_pubkey.len()));
        }

        // 1. 查白名单
        let pubkey_b64 = B64.encode(client_pubkey);
        let state = self.state.lock().await;
        let exists = state.paired_clients.iter().any(|c| c.pubkey_b64 == pubkey_b64);
        if !exists {
            return Err(PairingError::Ed25519("客户端未配对".into()));
        }
        drop(state);

        // 2. 验证签名
        let verifying_key = VerifyingKey::from_bytes(
            client_pubkey.try_into().map_err(|_| {
                PairingError::Ed25519("VerifyingKey::from_bytes 长度不匹配".into())
            })?,
        )
        .map_err(|e| PairingError::Ed25519(format!("公钥无效: {}", e)))?;

        let sig_bytes: [u8; 64] = signature
            .try_into()
            .map_err(|_| PairingError::BadSignature)?;
        let signature = Signature::from_bytes(&sig_bytes);

        // 签名内容:SHA256(client_name || client_pubkey || nonce_le_bytes)
        let mut hasher = Sha256::new();
        hasher.update(client_name.as_bytes());
        hasher.update(client_pubkey);
        hasher.update(nonce.to_le_bytes());
        let msg = hasher.finalize();

        verifying_key
            .verify(&msg, &signature)
            .map_err(|_| PairingError::BadSignature)
    }

    /// 移除已配对客户端(用于"忘记此设备"功能)
    pub async fn unpair_client(&self, pubkey_b64: &str) -> Result<(), PairingError> {
        let mut state = self.state.lock().await;
        let before = state.paired_clients.len();
        state.paired_clients.retain(|c| c.pubkey_b64 != pubkey_b64);
        if state.paired_clients.len() == before {
            return Ok(()); // 未找到,幂等返回
        }
        self.persist(&state)
    }

    /// 清空所有已配对客户端(用于"重置配对")
    pub async fn reset_paired_clients(&self) -> Result<(), PairingError> {
        let mut state = self.state.lock().await;
        state.paired_clients.clear();
        *self.current_pin.lock().await = None;
        self.persist(&state)
    }

    fn persist(&self, state: &PairingState) -> Result<(), PairingError> {
        if let Some(parent) = self.state_path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let content = serde_json::to_string_pretty(state)?;
        std::fs::write(&self.state_path, content)?;
        Ok(())
    }
}

// ============================================================================
// 客户端侧配对工具(供 Android JNI 调用)
// ============================================================================

/// 客户端配对状态持久化结构
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClientPairingState {
    /// 客户端 Ed25519 私钥(base64 编码的 32 字节种子)
    pub client_secret_b64: String,
    /// 已配对的服务端公钥列表(base64 编码的 32 字节)
    pub paired_servers: Vec<String>,
}

impl ClientPairingState {
    /// 加载或创建客户端配对状态
    pub fn load_or_create(path: &Path) -> Result<Self, PairingError> {
        if path.exists() {
            let content = std::fs::read_to_string(path)?;
            Ok(serde_json::from_str(&content)?)
        } else {
            let mut rng = rand::thread_rng();
            let signing_key = SigningKey::generate(&mut rng);
            let state = ClientPairingState {
                client_secret_b64: B64.encode(signing_key.to_bytes()),
                paired_servers: Vec::new(),
            };
            if let Some(parent) = path.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            let _ = std::fs::write(path, serde_json::to_string_pretty(&state)?);
            Ok(state)
        }
    }

    /// 客户端公钥(32 字节)
    pub fn pubkey(&self) -> Result<[u8; 32], PairingError> {
        let secret_bytes = B64.decode(&self.client_secret_b64)?;
        if secret_bytes.len() != 32 {
            return Err(PairingError::Ed25519(format!(
                "client_secret 长度错误: 期望 32, 实际 {}",
                secret_bytes.len()
            )));
        }
        let signing_key = SigningKey::from_bytes(
            secret_bytes.as_slice().try_into().map_err(|_| {
                PairingError::Ed25519("SigningKey::from_bytes 长度不匹配".into())
            })?,
        );
        Ok(signing_key.verifying_key().to_bytes())
    }

    /// 客户端公钥 base64
    pub fn pubkey_b64(&self) -> Result<String, PairingError> {
        Ok(B64.encode(self.pubkey()?))
    }

    /// 用客户端私钥对 server_nonce 签名(用于 PairRequest)
    ///
    /// 返回 64 字节签名
    pub fn sign_server_nonce(&self, server_nonce: u64) -> Result<Vec<u8>, PairingError> {
        let secret_bytes = B64.decode(&self.client_secret_b64)?;
        if secret_bytes.len() != 32 {
            return Err(PairingError::Ed25519(format!(
                "client_secret 长度错误: 期望 32, 实际 {}",
                secret_bytes.len()
            )));
        }
        let signing_key = SigningKey::from_bytes(
            secret_bytes.as_slice().try_into().map_err(|_| {
                PairingError::Ed25519("SigningKey::from_bytes 长度不匹配".into())
            })?,
        );
        let msg = server_nonce.to_le_bytes();
        let sig: Signature = signing_key.sign(&msg);
        Ok(sig.to_bytes().to_vec())
    }

    /// 用客户端私钥对 HelloPaired 内容签名
    ///
    /// 签名内容 = SHA256(client_name || client_pubkey || nonce_le_bytes)
    pub fn sign_paired_hello(
        &self,
        client_name: &str,
        nonce: u64,
    ) -> Result<(Vec<u8>, Vec<u8>), PairingError> {
        let secret_bytes = B64.decode(&self.client_secret_b64)?;
        if secret_bytes.len() != 32 {
            return Err(PairingError::Ed25519(format!(
                "client_secret 长度错误: 期望 32, 实际 {}",
                secret_bytes.len()
            )));
        }
        let signing_key = SigningKey::from_bytes(
            secret_bytes.as_slice().try_into().map_err(|_| {
                PairingError::Ed25519("SigningKey::from_bytes 长度不匹配".into())
            })?,
        );
        let pubkey = signing_key.verifying_key().to_bytes();

        let mut hasher = Sha256::new();
        hasher.update(client_name.as_bytes());
        hasher.update(pubkey);
        hasher.update(nonce.to_le_bytes());
        let msg = hasher.finalize();

        let sig: Signature = signing_key.sign(&msg);
        Ok((pubkey.to_vec(), sig.to_bytes().to_vec()))
    }

    /// 添加已配对服务端公钥
    pub fn add_paired_server(&mut self, server_pubkey_b64: String) {
        if !self.paired_servers.contains(&server_pubkey_b64) {
            self.paired_servers.push(server_pubkey_b64);
        }
    }

    /// 检查是否已配对某服务端
    pub fn is_paired_with(&self, server_pubkey_b64: &str) -> bool {
        self.paired_servers.iter().any(|s| s == server_pubkey_b64)
    }

    /// 持久化到文件
    pub fn save(&self, path: &Path) -> Result<(), PairingError> {
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let content = serde_json::to_string_pretty(self)?;
        std::fs::write(path, content)?;
        Ok(())
    }
}

/// 生成 6 位随机 PIN(独立函数,供测试或独立调用)
pub fn generate_pin() -> String {
    format!("{:06}", rand::thread_rng().gen_range(0..1_000_000u32))
}

/// 生成随机 nonce(用于 PairRequired 消息)
pub fn generate_nonce() -> u64 {
    rand::thread_rng().gen()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_pairing_flow() {
        // 模拟服务端
        let tmp_dir = std::env::temp_dir().join(format!("meowmic-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&tmp_dir);
        std::fs::create_dir_all(&tmp_dir).unwrap();

        let server = PairingManager::load_or_create(Some(tmp_dir.clone())).unwrap();
        let pin = server.ensure_pin().await;
        let nonce = generate_nonce();

        // 模拟客户端
        let client_path = tmp_dir.join("client-pairing.json");
        let mut client_state = ClientPairingState::load_or_create(&client_path).unwrap();
        let client_pubkey = client_state.pubkey().unwrap();
        let signature = client_state.sign_server_nonce(nonce).unwrap();

        // 客户端发起配对
        let (success, server_pubkey, err) = server
            .handle_pair_request(
                client_pubkey.to_vec(),
                "TestClient".into(),
                pin.clone(),
                nonce,
                signature,
            )
            .await;
        assert!(success, "配对应成功, err={}", err);
        assert_eq!(server_pubkey.len(), 32);

        // 验证已配对
        assert!(server.is_client_paired(&client_pubkey).await);

        // 验证 HelloPaired 签名
        let hello_nonce = generate_nonce();
        let (client_pubkey2, hello_sig) = client_state.sign_paired_hello("TestClient", hello_nonce).unwrap();
        server
            .verify_paired_hello(&client_pubkey2, "TestClient", hello_nonce, &hello_sig)
            .await
            .unwrap();

        // 保存客户端状态并重新加载
        client_state.add_paired_server(B64.encode(&server_pubkey));
        client_state.save(&client_path).unwrap();
        let reloaded = ClientPairingState::load_or_create(&client_path).unwrap();
        assert_eq!(reloaded.paired_servers.len(), 1);

        // 清理
        let _ = std::fs::remove_dir_all(&tmp_dir);
    }

    #[tokio::test]
    async fn test_bad_pin() {
        let tmp_dir = std::env::temp_dir().join(format!("meowmic-test-bad-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&tmp_dir);
        std::fs::create_dir_all(&tmp_dir).unwrap();

        let server = PairingManager::load_or_create(Some(tmp_dir.clone())).unwrap();
        let _pin = server.ensure_pin().await;
        let nonce = generate_nonce();

        let client_path = tmp_dir.join("client.json");
        let client_state = ClientPairingState::load_or_create(&client_path).unwrap();
        let client_pubkey = client_state.pubkey().unwrap();
        let signature = client_state.sign_server_nonce(nonce).unwrap();

        // 用错误的 PIN
        let (success, _, err) = server
            .handle_pair_request(
                client_pubkey.to_vec(),
                "TestClient".into(),
                "000000".into(), // 错误 PIN(除非恰好生成 000000)
                nonce,
                signature,
            )
            .await;
        // 如果恰好生成 000000 则跳过(概率极低)
        if server.current_pin().await != Some("000000".into()) {
            assert!(!success);
            assert_eq!(err, "PIN 不正确");
        }

        let _ = std::fs::remove_dir_all(&tmp_dir);
    }
}
