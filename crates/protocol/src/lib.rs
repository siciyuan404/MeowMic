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
}
