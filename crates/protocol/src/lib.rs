//! MeowMic 协议定义
//!
//! 设计原则:
//! - UDP 数据通道用大端(网络字节序)手动读写,零依赖、零 UB
//! - TCP 控制通道用 bincode(变长消息)
//! - 所有包带纳秒时间戳 + 序号,用于时钟同步与抖动补偿

#![forbid(unsafe_op_in_unsafe_fn)]

use bytes::{Buf, BufMut};
use serde::{Deserialize, Serialize};
use thiserror::Error;

/// 协议魔数,用于快速识别 MeowMic 包
pub const MAGIC: u16 = 0x4D4D; // "MM"

/// 解析错误
#[derive(Debug, Error)]
pub enum ProtocolError {
    #[error("包过短: 需要 {need} 字节, 实际 {got}")]
    TooShort { need: usize, got: usize },
    #[error("魔数不匹配: 期望 0x{expected:04X}, 实际 0x{got:04X}")]
    BadMagic { expected: u16, got: u16 },
    #[error("未知触摸事件类型: 0x{0:02X}")]
    UnknownTouchEventType(u8),
    #[error("bincode 序列化失败: {0}")]
    Bincode(#[from] bincode::Error),
}

// ============================================================================
// UDP 包:大端字节序手动读写
// ============================================================================

/// 通用包头(8 字节)
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Header {
    pub magic: u16,
    pub seq: u16,
    pub ts_ns: u32,
}

pub const HEADER_LEN: usize = 8;

impl Header {
    pub fn encode(&self, dst: &mut impl BufMut) {
        dst.put_u16(self.magic);
        dst.put_u16(self.seq);
        dst.put_u32(self.ts_ns);
    }

    pub fn decode(buf: &mut &[u8]) -> Result<Self, ProtocolError> {
        if buf.remaining() < HEADER_LEN {
            return Err(ProtocolError::TooShort {
                need: HEADER_LEN,
                got: buf.remaining(),
            });
        }
        let magic = buf.get_u16();
        let seq = buf.get_u16();
        let ts_ns = buf.get_u32();
        Ok(Self { magic, seq, ts_ns })
    }
}

/// 触摸事件类型
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum TouchEventType {
    Down = 0x01,
    Move = 0x02,
    Up = 0x03,
    Button = 0x04,
    Scroll = 0x05,
}

/// 触摸数据负载(12 字节)
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TouchPayload {
    pub event_type: u8,
    /// 按钮掩码,语义随 `event_type` 变化:
    /// - `Button` 事件:bit0=左 bit1=右 bit2=中,表示当前触发哪些键
    /// - `Scroll` 事件:bit0=垂直滚动(dy 有效,默认)
    ///                 bit1=水平滚动(dx 有效)
    ///                 bit2=缩放(dy>0 放大 / dy<0 缩小,PC 端模拟 Ctrl+滚轮)
    ///                 bit0+bit2=惯性衰减帧(不再加速,仅延续)
    /// - `Down/Move/Up` 事件:未使用,置 0
    pub button_mask: u8,
    /// 相对 X 位移(定点 Q16.16)
    pub dx_q16: i32,
    /// 相对 Y 位移
    pub dy_q16: i32,
    /// 压力 [0, 255]
    pub pressure: u8,
}

pub const TOUCH_PAYLOAD_LEN: usize = 12;

impl TouchPayload {
    pub fn encode(&self, dst: &mut impl BufMut) {
        dst.put_u8(self.event_type);
        dst.put_u8(self.button_mask);
        dst.put_i32(self.dx_q16);
        dst.put_i32(self.dy_q16);
        dst.put_u8(self.pressure);
        dst.put_u8(0); // reserved,凑齐 12 字节
    }

    pub fn decode(buf: &mut &[u8]) -> Result<Self, ProtocolError> {
        if buf.remaining() < TOUCH_PAYLOAD_LEN {
            return Err(ProtocolError::TooShort {
                need: TOUCH_PAYLOAD_LEN,
                got: buf.remaining(),
            });
        }
        let event_type = buf.get_u8();
        let button_mask = buf.get_u8();
        let dx_q16 = buf.get_i32();
        let dy_q16 = buf.get_i32();
        let pressure = buf.get_u8();
        let _reserved = buf.get_u8();
        Ok(Self {
            event_type,
            button_mask,
            dx_q16,
            dy_q16,
            pressure,
        })
    }
}

/// 完整触摸包(Header + TouchPayload,共 20 字节)
#[derive(Debug, Clone, Copy)]
pub struct TouchPacket {
    pub header: Header,
    pub payload: TouchPayload,
}

impl TouchPacket {
    pub fn new(seq: u16, ts_ns: u32, event: TouchEventType, dx: f32, dy: f32) -> Self {
        Self::new_with_button(seq, ts_ns, event, 0, dx, dy)
    }

    /// 带按钮掩码的构造函数
    ///
    /// `button_mask` 位定义: bit0=左键 bit1=右键 bit2=中键
    /// 对于 `TouchEventType::Button` 事件,`dx` 低 16 位表示按下(1)/抬起(0),
    /// 这里用 `button_mask` 直接表达"哪些键被按下/释放",由 server 端解释。
    pub fn new_with_button(
        seq: u16,
        ts_ns: u32,
        event: TouchEventType,
        button_mask: u8,
        dx: f32,
        dy: f32,
    ) -> Self {
        let payload = TouchPayload {
            event_type: event as u8,
            button_mask,
            dx_q16: (dx * 65536.0) as i32,
            dy_q16: (dy * 65536.0) as i32,
            pressure: 0,
        };
        let header = Header {
            magic: MAGIC,
            seq,
            ts_ns,
        };
        Self { header, payload }
    }

    pub fn encode(&self, dst: &mut impl BufMut) {
        self.header.encode(dst);
        self.payload.encode(dst);
    }

    pub fn encode_to_vec(&self) -> Vec<u8> {
        let mut v = Vec::with_capacity(HEADER_LEN + TOUCH_PAYLOAD_LEN);
        self.encode(&mut v);
        v
    }

    pub fn decode(buf: &[u8]) -> Result<Self, ProtocolError> {
        let mut cur = buf;
        let header = Header::decode(&mut cur)?;
        if header.magic != MAGIC {
            return Err(ProtocolError::BadMagic {
                expected: MAGIC,
                got: header.magic,
            });
        }
        let payload = TouchPayload::decode(&mut cur)?;
        Ok(Self { header, payload })
    }

    pub fn dx(&self) -> f32 {
        self.payload.dx_q16 as f32 / 65536.0
    }
    pub fn dy(&self) -> f32 {
        self.payload.dy_q16 as f32 / 65536.0
    }
    pub fn event_type(&self) -> Result<TouchEventType, ProtocolError> {
        match self.payload.event_type {
            0x01 => Ok(TouchEventType::Down),
            0x02 => Ok(TouchEventType::Move),
            0x03 => Ok(TouchEventType::Up),
            0x04 => Ok(TouchEventType::Button),
            0x05 => Ok(TouchEventType::Scroll),
            v => Err(ProtocolError::UnknownTouchEventType(v)),
        }
    }
}

/// 音频包(Header + Opus payload),负载长度可变
#[derive(Debug, Clone)]
pub struct AudioPacket<'a> {
    pub header: Header,
    pub opus: &'a [u8],
}

impl<'a> AudioPacket<'a> {
    pub fn new(seq: u16, ts_ns: u32, opus: &'a [u8]) -> Self {
        let header = Header {
            magic: MAGIC,
            seq,
            ts_ns,
        };
        Self { header, opus }
    }

    pub fn encode(&self, dst: &mut impl BufMut) {
        self.header.encode(dst);
        dst.put_slice(self.opus);
    }

    pub fn encode_to_vec(&self) -> Vec<u8> {
        let mut v = Vec::with_capacity(HEADER_LEN + self.opus.len());
        self.encode(&mut v);
        v
    }

    pub fn decode(buf: &'a [u8]) -> Result<Self, ProtocolError> {
        if buf.len() < HEADER_LEN {
            return Err(ProtocolError::TooShort {
                need: HEADER_LEN,
                got: buf.len(),
            });
        }
        let mut cur = buf;
        let header = Header::decode(&mut cur)?;
        if header.magic != MAGIC {
            return Err(ProtocolError::BadMagic {
                expected: MAGIC,
                got: header.magic,
            });
        }
        Ok(Self {
            header,
            opus: cur,
        })
    }
}

// ============================================================================
// UDP 视频分片包:大端字节序手动读写
// ============================================================================

/// 视频魔数(区别于通用 MAGIC "MM")
pub const VIDEO_MAGIC: u16 = 0x4D56; // "MV"

/// 视频分片包头(8 字节)
///
/// 一个 H.264 NALU 可能超过 MTU,需要分片为多个 UDP 包。
/// 同一帧的所有分片共享 `frame_id`,接收端按 `frame_id` 重组。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VideoFragment {
    pub magic: u16,       // VIDEO_MAGIC = 0x4D56
    pub frame_id: u16,    // 帧序号(同一帧所有分片共享)
    pub frag_idx: u8,     // 当前分片序号 (0-based)
    pub frag_total: u8,   // 总分片数(不含 FEC 包)
    pub nal_type: u8,     // H.264 NALU 类型 (7=SPS, 8=PPS, 5=IDR, 1=P-frame)
    /// bit0: is_keyframe
    /// bit1: is_last_fragment
    /// bit2: is_fec (XOR 冗余包,内容为所有原始分片的异或;
    ///        frag_idx=frag_total, frag_total=原始分片数;接收端用其恢复单包丢失)
    pub flags: u8,
}

pub const VIDEO_FRAGMENT_HEADER_LEN: usize = 8;

/// 单个 UDP 包最大 payload (MTU 安全:1500 - IP 20 - UDP 8 - VideoFragment 8 = 1464)
pub const MAX_VIDEO_PAYLOAD: usize = 1400;

/// FEC 包固定 padding 长度(所有原始分片按最大长度对齐后异或,
/// 接收端按此长度恢复,无需长度前缀)
pub const FEC_PAD_LEN: usize = MAX_VIDEO_PAYLOAD;

impl VideoFragment {
    pub fn encode(&self, dst: &mut impl BufMut) {
        dst.put_u16(self.magic);
        dst.put_u16(self.frame_id);
        dst.put_u8(self.frag_idx);
        dst.put_u8(self.frag_total);
        dst.put_u8(self.nal_type);
        dst.put_u8(self.flags);
    }

    pub fn decode(buf: &mut &[u8]) -> Result<Self, ProtocolError> {
        if buf.remaining() < VIDEO_FRAGMENT_HEADER_LEN {
            return Err(ProtocolError::TooShort {
                need: VIDEO_FRAGMENT_HEADER_LEN,
                got: buf.remaining(),
            });
        }
        let magic = buf.get_u16();
        let frame_id = buf.get_u16();
        let frag_idx = buf.get_u8();
        let frag_total = buf.get_u8();
        let nal_type = buf.get_u8();
        let flags = buf.get_u8();
        Ok(Self { magic, frame_id, frag_idx, frag_total, nal_type, flags })
    }

    pub fn is_keyframe(&self) -> bool {
        self.flags & 0x01 != 0
    }

    pub fn is_last(&self) -> bool {
        self.flags & 0x02 != 0
    }

    /// 是否为 XOR 冗余包(FEC)
    pub fn is_fec(&self) -> bool {
        self.flags & 0x04 != 0
    }
}

/// 从 H.264 NALU 字节流中提取 NALU 类型
///
/// Annex-B 格式: [00 00 00 01] 或 [00 00 01] 起始码 + NALU 数据
/// NALU 类型 = NALU 第一字节 & 0x1F
pub fn nal_type_from_nalu(nalu: &[u8]) -> u8 {
    // 跳过起始码
    let mut i = 0;
    while i < nalu.len() && nalu[i] == 0 {
        i += 1;
    }
    if i < nalu.len() && (nalu[i] == 1) {
        i += 1;
    }
    if i < nalu.len() {
        nalu[i] & 0x1F
    } else {
        0
    }
}

/// 将一个 NALU 分片为多个 UDP 包(每个 ≤ MAX_VIDEO_PAYLOAD)
///
/// 返回 Vec<(VideoFragment, Vec<u8>)>,每个元素是一个分片
pub fn fragment_nalu(nalu: &[u8], frame_id: u16, is_keyframe: bool) -> Vec<(VideoFragment, Vec<u8>)> {
    let nal_type = nal_type_from_nalu(nalu);
    let total_len = nalu.len();
    let frag_count = ((total_len + MAX_VIDEO_PAYLOAD - 1) / MAX_VIDEO_PAYLOAD).max(1) as u8;

    let mut fragments = Vec::with_capacity(frag_count as usize);
    let mut offset = 0;
    for idx in 0..frag_count {
        let end = (offset + MAX_VIDEO_PAYLOAD).min(total_len);
        let chunk = nalu[offset..end].to_vec();
        let is_last = idx == frag_count - 1;
        let mut flags = 0u8;
        if is_keyframe { flags |= 0x01; }
        if is_last { flags |= 0x02; }
        let frag = VideoFragment {
            magic: VIDEO_MAGIC,
            frame_id,
            frag_idx: idx,
            frag_total: frag_count,
            nal_type,
            flags,
        };
        fragments.push((frag, chunk));
        offset = end;
    }
    fragments
}

/// 为一组原始分片生成 1 个 XOR 冗余包(FEC)
///
/// - 仅对 `frag_total >= 2` 的 NALU 生成 FEC;单分片 NALU 不需要
/// - 所有原始分片按 `FEC_PAD_LEN` 零填充对齐后逐字节异或
/// - FEC 包 `frag_idx = frag_total`(落在原始分片之后),flags 仅置 bit2(is_fec)
///   (不置 bit1 is_last:它不是 NALU 的最后一段;接收端按 is_fec 单独识别)
///
/// 返回 Some((frag, payload)) 用于单包丢失恢复;frag_total < 2 时返回 None
pub fn xor_fragments(fragments: &[(VideoFragment, Vec<u8>)]) -> Option<(VideoFragment, Vec<u8>)> {
    if fragments.is_empty() {
        return None;
    }
    let frag_total = fragments[0].0.frag_total;
    if frag_total < 2 {
        return None;
    }
    let mut fec = vec![0u8; FEC_PAD_LEN];
    for (_frag, payload) in fragments {
        for (i, b) in payload.iter().enumerate() {
            fec[i] ^= b;
        }
    }
    let mut flags = 0u8;
    flags |= 0x04; // is_fec
    if fragments[0].0.is_keyframe() {
        flags |= 0x01;
    }
    let frag = VideoFragment {
        magic: VIDEO_MAGIC,
        frame_id: fragments[0].0.frame_id,
        frag_idx: frag_total,
        frag_total,
        nal_type: fragments[0].0.nal_type,
        flags,
    };
    Some((frag, fec))
}

/// 接收端:用 FEC 包恢复缺失的原始分片
///
/// - `have`:已收到的原始分片(`frag_idx < frag_total`),按 frag_idx 索引
/// - `fec_payload`:XOR 冗余包负载(长度 = FEC_PAD_LEN)
///
/// 返回恢复出的缺失分片索引和 payload;
/// 若缺失数 != 1 或无缺失,返回 None(无法恢复多包丢失)
pub fn recover_with_fec(
    have: &[(u8, &[u8])], // (frag_idx, payload_slice)
    fec_payload: &[u8],
    frag_total: u8,
) -> Option<(u8, Vec<u8>)> {
    if frag_total < 2 || fec_payload.len() < FEC_PAD_LEN {
        return None;
    }
    // 已收到的原始分片集合
    let mut have_set = [false; 256];
    let mut have_count = 0u32;
    for (idx, _payload) in have {
        if (*idx as usize) < frag_total as usize && !have_set[*idx as usize] {
            have_set[*idx as usize] = true;
            have_count += 1;
        }
    }
    let missing = frag_total as u32 - have_count;
    if missing != 1 {
        return None;
    }
    // 找出缺失的分片索引
    let missing_idx = (0..frag_total).find(|i| !have_set[*i as usize])?;
    // XOR:缺失分片 = FEC ⊕ 已有分片们
    let mut recovered = fec_payload.to_vec();
    for (_, payload) in have {
        for (i, b) in payload.iter().enumerate() {
            recovered[i] ^= b;
        }
    }
    Some((missing_idx, recovered))
}

// ============================================================================
// TCP 控制消息:变长,bincode 序列化
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum ControlMessage {
    Hello {
        client_name: String,
        protocol_version: u32,
        audio_sample_rate: u32,
        audio_channels: u8,
        audio_frame_ms: u16,
    },
    HelloAck {
        server_name: String,
        protocol_version: u32,
        client_id: u32,
        audio_sample_rate: u32,
        audio_channels: u8,
        audio_frame_ms: u16,
    },
    /// 已配对客户端的 Hello:带公钥 + 签名证明身份
    ///
    /// 签名内容 = SHA256(client_name || client_pubkey || nonce)
    /// 服务端查白名单匹配 client_pubkey,验证签名后放行
    HelloPaired {
        client_name: String,
        protocol_version: u32,
        client_pubkey: Vec<u8>,
        nonce: u64,
        signature: Vec<u8>,
        audio_sample_rate: u32,
        audio_channels: u8,
        audio_frame_ms: u16,
    },
    /// 服务端要求客户端先完成配对
    /// server_pubkey:服务端 Ed25519 公钥(base64 解码后的原始 32 字节)
    /// server_nonce:服务端生成的随机 nonce,客户端需在 PairRequest 中回传签名
    PairRequired {
        server_pubkey: Vec<u8>,
        server_nonce: u64,
    },
    /// 客户端发起配对请求(首次连接或重新配对)
    ///
    /// - client_pubkey:客户端 Ed25519 公钥(32 字节)
    /// - client_name:设备名(用于服务端白名单显示)
    /// - pin:用户在 PC 端看到的 6 位 PIN
    /// - signature:对 server_nonce 的 Ed25519 签名(用客户端私钥)
    ///   验证签名可以证明客户端确实持有对应私钥,防止公钥冒充
    PairRequest {
        client_pubkey: Vec<u8>,
        client_name: String,
        pin: String,
        server_nonce: u64,
        signature: Vec<u8>,
    },
    /// 服务端配对响应
    ///
    /// - success=true:配对成功,server_pubkey 可用于后续验证服务端身份
    /// - success=false:配对失败(PIN 错误/已配对满/服务端关闭配对),error_msg 描述原因
    PairResponse {
        success: bool,
        server_pubkey: Vec<u8>,
        error_msg: String,
    },
    SyncReq { client_ts_ns: u64 },
    SyncResp {
        client_ts_ns: u64,
        server_recv_ts_ns: u64,
        server_send_ts_ns: u64,
    },
    Stats {
        touch_packets_recv: u64,
        audio_packets_recv: u64,
        touch_packets_lost: u64,
        audio_packets_lost: u64,
    },
    /// 客户端通知服务端切换外放静音状态
    /// muted=true 时,服务端丢弃 PCM 不输出到扬声器
    SetMuteSpeaker { muted: bool },
    /// 键盘事件(模拟键盘按下/抬起)
    /// key_code 为 Windows VK code,is_down=true 按下/false 抬起
    /// 走 TCP 控制通道(可靠传递,快捷键低频)
    KeyEvent { key_code: u16, is_down: bool },
    /// 客户端请求开始视频推流(UDP push 模式)
    /// 客户端发送后,服务端在 video 端口 (base+6) 持续推送 H.264 分片
    /// `client_video_port`:客户端绑定的 video UDP 接收端口(0.0.0.0:port),
    /// 服务端 push 目标 = SocketAddr::new(peer.ip(), client_video_port)
    StartVideo {
        width: u32,
        height: u32,
        fps: u32,
        bitrate: u32,
        client_video_port: u16,
    },
    /// 服务端确认视频推流已开始
    VideoStarted {
        width: u32,
        height: u32,
        fps: u32,
        codec: u8, // 0=H.264, 1=HEVC(预留)
    },
    /// 客户端请求停止视频推流
    StopVideo,
    /// 客户端→服务端:周期性视频接收统计(用于自适应码率)
    ///
    /// - `received_frames`:自上次上报以来收到的完整帧数
    /// - `lost_frames`:自上次上报以来丢失的帧数(frame_id 跳跃 + FEC 恢复失败)
    /// - `recovered_frames`:FEC 成功恢复的帧数
    /// - `rtt_ms`:客户端测得的 RTT(若有 ClockSync,否则 0)
    VideoStats {
        received_frames: u32,
        lost_frames: u32,
        recovered_frames: u32,
        rtt_ms: u32,
    },
    Ping,
    Pong,
    Bye,
}

/// 控制消息长度前缀编码(4 字节 u32 LE + payload)
pub fn encode_control(msg: &ControlMessage, dst: &mut Vec<u8>) -> Result<(), ProtocolError> {
    dst.clear();
    dst.extend_from_slice(&[0u8; 4]);
    bincode::serialize_into(&mut *dst, msg)?;
    let len = (dst.len() - 4) as u32;
    dst[..4].copy_from_slice(&len.to_le_bytes());
    Ok(())
}

pub fn decode_control(buf: &[u8]) -> Result<(ControlMessage, usize), ProtocolError> {
    if buf.len() < 4 {
        return Err(ProtocolError::TooShort {
            need: 4,
            got: buf.len(),
        });
    }
    let len = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
    let total = 4 + len;
    if buf.len() < total {
        return Err(ProtocolError::TooShort {
            need: total,
            got: buf.len(),
        });
    }
    let msg: ControlMessage = bincode::deserialize(&buf[4..total])?;
    Ok((msg, total))
}

// ============================================================================
// 时钟同步
// ============================================================================

#[derive(Debug, Clone, Copy, Default)]
pub struct ClockOffset {
    pub offset_ns: i64,
    pub rtt_ns: u64,
}

impl ClockOffset {
    pub fn from_sync(
        client_ts_ns: u64,
        server_recv_ts_ns: u64,
        server_send_ts_ns: u64,
        client_recv_ts_ns: u64,
    ) -> Self {
        let rtt_ns = client_recv_ts_ns.saturating_sub(client_ts_ns)
            - server_send_ts_ns.saturating_sub(server_recv_ts_ns);
        let offset_ns = (server_recv_ts_ns as i64 - client_ts_ns as i64
            + server_send_ts_ns as i64
            - client_recv_ts_ns as i64)
            / 2;
        Self { offset_ns, rtt_ns }
    }

    pub fn client_to_server(&self, client_ns: u64) -> u64 {
        (client_ns as i64 - self.offset_ns) as u64
    }

    pub fn server_to_client(&self, server_ns: u64) -> u64 {
        (server_ns as i64 + self.offset_ns) as u64
    }
}

/// 单调时钟纳秒
pub fn monotonic_ns() -> u64 {
    use std::time::Instant;
    use std::sync::OnceLock;
    static EPOCH: OnceLock<Instant> = OnceLock::new();
    let epoch = EPOCH.get_or_init(Instant::now);
    epoch.elapsed().as_nanos() as u64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn header_roundtrip() {
        let h = Header {
            magic: MAGIC,
            seq: 42,
            ts_ns: 12345,
        };
        let mut buf = Vec::new();
        h.encode(&mut buf);
        assert_eq!(buf.len(), HEADER_LEN);
        let mut cur: &[u8] = &buf;
        let decoded = Header::decode(&mut cur).unwrap();
        assert_eq!(decoded, h);
    }

    #[test]
    fn touch_packet_roundtrip() {
        let pkt = TouchPacket::new(42, 12345, TouchEventType::Move, 1.5, -2.25);
        let buf = pkt.encode_to_vec();
        assert_eq!(buf.len(), HEADER_LEN + TOUCH_PAYLOAD_LEN);
        let decoded = TouchPacket::decode(&buf).unwrap();
        assert_eq!(decoded.header.seq, 42);
        assert_eq!(decoded.header.ts_ns, 12345);
        assert_eq!(decoded.event_type().unwrap(), TouchEventType::Move);
        assert!((decoded.dx() - 1.5).abs() < 1e-3);
        assert!((decoded.dy() - (-2.25)).abs() < 1e-3);
    }

    #[test]
    fn audio_packet_roundtrip() {
        let opus = [1u8, 2, 3, 4, 5];
        let pkt = AudioPacket::new(7, 999, &opus);
        let buf = pkt.encode_to_vec();
        let decoded = AudioPacket::decode(&buf).unwrap();
        assert_eq!(decoded.header.seq, 7);
        assert_eq!(decoded.opus, &opus);
    }

    #[test]
    fn control_message_roundtrip() {
        let msg = ControlMessage::Hello {
            client_name: "test".into(),
            protocol_version: 1,
            audio_sample_rate: 48000,
            audio_channels: 1,
            audio_frame_ms: 20,
        };
        let mut buf = Vec::new();
        encode_control(&msg, &mut buf).unwrap();
        let (decoded, consumed) = decode_control(&buf).unwrap();
        assert_eq!(consumed, buf.len());
        match decoded {
            ControlMessage::Hello {
                audio_sample_rate, ..
            } => assert_eq!(audio_sample_rate, 48000),
            _ => panic!("wrong variant"),
        }
    }

    #[test]
    fn clock_offset_symmetric() {
        // 客户端比服务端快 1s,单程延迟 50ns(对称)
        // t0=client_send t1=server_recv t2=server_send t3=client_recv
        let offset = ClockOffset::from_sync(1_000_000_000, 50, 50, 1_000_000_100);
        assert_eq!(offset.rtt_ns, 100);
        assert_eq!(offset.offset_ns, -1_000_000_000);
    }

    #[test]
    fn video_fragment_roundtrip() {
        let frag = VideoFragment {
            magic: VIDEO_MAGIC,
            frame_id: 42,
            frag_idx: 1,
            frag_total: 3,
            nal_type: 5, // IDR
            flags: 0x01, // keyframe
        };
        let mut buf = Vec::new();
        frag.encode(&mut buf);
        assert_eq!(buf.len(), VIDEO_FRAGMENT_HEADER_LEN);
        let mut cur: &[u8] = &buf;
        let decoded = VideoFragment::decode(&mut cur).unwrap();
        assert_eq!(decoded, frag);
        assert!(decoded.is_keyframe());
        assert!(!decoded.is_last());
    }

    #[test]
    fn fragment_nalu_single() {
        // 小 NALU,单分片
        let nalu = [0u8, 0, 0, 1, 0x65, 0xAA, 0xBB]; // IDR
        let frags = fragment_nalu(&nalu, 0, true);
        assert_eq!(frags.len(), 1);
        assert!(frags[0].0.is_last());
        assert!(frags[0].0.is_keyframe());
        assert_eq!(frags[0].0.frag_total, 1);
    }

    #[test]
    fn fragment_nalu_multi() {
        // 大 NALU,多分片
        let mut nalu = vec![0u8, 0, 0, 1, 0x65]; // IDR header
        nalu.extend(vec![0xAA; MAX_VIDEO_PAYLOAD * 2 + 100]); // 超过 2 个分片
        let frags = fragment_nalu(&nalu, 10, true);
        assert_eq!(frags.len(), 3);
        assert!(!frags[0].0.is_last());
        assert!(!frags[1].0.is_last());
        assert!(frags[2].0.is_last());
        // 验证重组后数据一致
        let mut reassembled = Vec::new();
        for (_frag, data) in &frags {
            reassembled.extend_from_slice(data);
        }
        assert_eq!(reassembled, nalu);
    }

    #[test]
    fn xor_fragments_skip_single() {
        // 单分片 NALU 不生成 FEC
        let nalu = vec![0u8, 0, 0, 1, 0x65, 0xAA, 0xBB];
        let frags = fragment_nalu(&nalu, 0, true);
        assert_eq!(frags.len(), 1);
        assert!(xor_fragments(&frags).is_none());
    }

    #[test]
    fn xor_fragments_roundtrip() {
        // 3 分片 NALU,丢任意 1 个,用 FEC 恢复
        let mut nalu = vec![0u8, 0, 0, 1, 0x65]; // IDR
        nalu.extend(vec![0x55u8; MAX_VIDEO_PAYLOAD * 2 + 200]); // 3 分片
        let frags = fragment_nalu(&nalu, 42, true);
        assert_eq!(frags.len(), 3);
        let (fec_frag, fec_payload) = xor_fragments(&frags).unwrap();
        assert!(fec_frag.is_fec());
        assert_eq!(fec_frag.frag_idx, 3);
        assert_eq!(fec_frag.frag_total, 3);

        // 丢弃 frag_idx=1,用 FEC 恢复
        let have: Vec<(u8, &[u8])> = vec![
            (frags[0].0.frag_idx, frags[0].1.as_slice()),
            (frags[2].0.frag_idx, frags[2].1.as_slice()),
        ];
        let (missing_idx, recovered) = recover_with_fec(&have, &fec_payload, 3).unwrap();
        assert_eq!(missing_idx, 1);
        assert_eq!(recovered.len(), frags[1].1.len());
        assert_eq!(recovered.as_slice(), frags[1].1.as_slice());

        // 完整重组(已恢复)应等于原 NALU
        let mut reassembled = Vec::new();
        reassembled.extend_from_slice(&frags[0].1);
        reassembled.extend_from_slice(&recovered);
        reassembled.extend_from_slice(&frags[2].1);
        assert_eq!(reassembled, nalu);
    }

    #[test]
    fn recover_with_fec_no_recovery_for_multi_loss() {
        // 丢 2 个分片,FEC 无法恢复
        let mut nalu = vec![0u8, 0, 0, 1, 0x65];
        nalu.extend(vec![0x33u8; MAX_VIDEO_PAYLOAD * 2 + 200]);
        let frags = fragment_nalu(&nalu, 7, false);
        let (_, fec_payload) = xor_fragments(&frags).unwrap();
        // 只保留 frag_idx=0
        let have: Vec<(u8, &[u8])> = vec![(frags[0].0.frag_idx, frags[0].1.as_slice())];
        assert!(recover_with_fec(&have, &fec_payload, 3).is_none());
    }

    #[test]
    fn recover_with_fec_no_missing() {
        // 无丢失时不需要恢复
        let mut nalu = vec![0u8, 0, 0, 1, 0x65];
        nalu.extend(vec![0x77u8; MAX_VIDEO_PAYLOAD * 2 + 100]);
        let frags = fragment_nalu(&nalu, 9, true);
        let (_, fec_payload) = xor_fragments(&frags).unwrap();
        let have: Vec<(u8, &[u8])> = frags
            .iter()
            .map(|(f, p)| (f.frag_idx, p.as_slice()))
            .collect();
        assert!(recover_with_fec(&have, &fec_payload, 3).is_none());
    }
}
