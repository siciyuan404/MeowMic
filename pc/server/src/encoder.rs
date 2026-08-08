//! H.264 / HEVC 硬件编码器(Media Foundation)
//!
//! 使用 Windows Media Foundation 的编码器 MFT,自动选择最佳硬件编码器:
//! - NVIDIA NVENC(GeForce 显卡)
//! - AMD AMF(Radeon 显卡)
//! - Intel QuickSync(核显)
//! - 软件 fallback(无 GPU 时)
//!
//! 支持两种 codec:
//! - `Codec::H264`:H.264 / AVC,主路径,所有 Windows 8+ 默认支持
//! - `Codec::Hevc`:HEVC / H.265,需硬件编码器(NVENC HEVC / AMF HEVC / QSV HEVC),
//!   同等码率下文字清晰度优于 H.264。运行时探测,失败自动回退 H.264。
//!
//! 输入: BGRA32 像素
//! 输出: NALU 字节流(Annex-B 格式,带起始码 00 00 00 01)

/// 编码器类型
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Codec {
    /// H.264 / AVC(默认,所有平台支持)
    H264,
    /// HEVC / H.265(需硬件支持,运行时探测)
    Hevc,
}

#[cfg(windows)]
mod mf_encoder {
    use std::sync::{Mutex, OnceLock};

    use windows::core::Interface;
    use windows::Win32::Media::MediaFoundation::{
        MFCreateMemoryBuffer, MFCreateMediaType, MFCreateSample, MFStartup,
        IMFSample, IMFTransform, MF_API_VERSION, MF_MT_AVG_BITRATE,
        MF_MT_FRAME_RATE, MF_MT_FRAME_SIZE, MF_MT_INTERLACE_MODE, MF_MT_MAJOR_TYPE,
        MF_MT_SUBTYPE, MF_MT_VIDEO_NOMINAL_RANGE, MFSTARTUP_LITE, MFMediaType_Video,
        MFNominalRange_0_255, MFVideoFormat_H264, MFVideoFormat_HEVC, MFVideoFormat_RGB32,
        MFVideoInterlace_Progressive, MFTEnumEx, MFT_ENUM_FLAG, MFT_ENUM_FLAG_HARDWARE,
        MFT_ENUM_FLAG_SYNCMFT, MFT_MESSAGE_COMMAND_DRAIN, MFT_MESSAGE_NOTIFY_BEGIN_STREAMING,
        MFT_MESSAGE_NOTIFY_END_OF_STREAM, MFT_MESSAGE_NOTIFY_END_STREAMING,
        MFT_MESSAGE_NOTIFY_START_OF_STREAM, MFT_OUTPUT_DATA_BUFFER, MFT_REGISTER_TYPE_INFO,
        MFT_CATEGORY_VIDEO_ENCODER,
    };
    use windows::Win32::System::Com::CoTaskMemFree;

    use super::Codec;

    /// Media Foundation 编码器封装(支持 H.264 / HEVC)
    pub struct MFEncoder {
        transform: IMFTransform,
        codec: Codec,
        width: u32,
        height: u32,
        frame_rate: u32,
        avg_bitrate: u32,
        frame_index: u64,
        initialized: bool,
    }

    unsafe impl Send for MFEncoder {}

    impl MFEncoder {
        /// 创建编码器(根据 codec 选择 H.264 或 HEVC MFT)
        pub fn new(
            codec: Codec,
            width: u32,
            height: u32,
            frame_rate: u32,
            avg_bitrate: u32,
        ) -> Option<Self> {
            unsafe {
                // 初始化 MF(如果尚未初始化)
                static MF_STARTED: OnceLock<()> = OnceLock::new();
                MF_STARTED.get_or_init(|| {
                    let _ = MFStartup(MF_API_VERSION, MFSTARTUP_LITE);
                });

                let transform = Self::enum_encoder(codec)?;
                let mut encoder = Self {
                    transform,
                    codec,
                    width,
                    height,
                    frame_rate,
                    avg_bitrate,
                    frame_index: 0,
                    initialized: false,
                };
                encoder.configure()?;
                Some(encoder)
            }
        }

        /// 返回使用的 codec
        pub fn codec(&self) -> Codec {
            self.codec
        }

        /// 枚举编码器 MFT(优先硬件,回退软件)
        unsafe fn enum_encoder(codec: Codec) -> Option<IMFTransform> {
            let subtype = match codec {
                Codec::H264 => MFVideoFormat_H264,
                Codec::Hevc => MFVideoFormat_HEVC,
            };
            let output_type_info = MFT_REGISTER_TYPE_INFO {
                guidMajorType: MFMediaType_Video,
                guidSubtype: subtype,
            };

            // 优先硬件编码器
            let hw_flags = MFT_ENUM_FLAG(MFT_ENUM_FLAG_HARDWARE.0 | MFT_ENUM_FLAG_SYNCMFT.0);
            if let Some(transform) = Self::enum_with_flags(hw_flags, &output_type_info) {
                return Some(transform);
            }

            // 回退到软件编码器(HEVC 软件编码器在大多数 Windows 上不存在)
            Self::enum_with_flags(MFT_ENUM_FLAG_SYNCMFT, &output_type_info)
        }

        /// 用指定 flags 枚举并激活第一个可用的编码器 MFT
        unsafe fn enum_with_flags(
            flags: MFT_ENUM_FLAG,
            output_type_info: &MFT_REGISTER_TYPE_INFO,
        ) -> Option<IMFTransform> {
            use windows::Win32::Media::MediaFoundation::IMFActivate;
            let mut activates_ptr: *mut Option<IMFActivate> = std::ptr::null_mut();
            let mut count: u32 = 0;

            let ok = MFTEnumEx(
                MFT_CATEGORY_VIDEO_ENCODER,
                flags,
                None,
                Some(output_type_info as *const _),
                &mut activates_ptr,
                &mut count,
            )
            .is_ok();

            let mut found: Option<IMFTransform> = None;
            if ok && count > 0 && !activates_ptr.is_null() {
                let activates = std::slice::from_raw_parts(activates_ptr, count as usize);
                for activate in activates {
                    if let Some(ref act) = activate {
                        if let Ok(transform) = act.ActivateObject::<IMFTransform>() {
                            found = Some(transform);
                            break;
                        }
                    }
                }
            }
            if !activates_ptr.is_null() {
                CoTaskMemFree(Some(activates_ptr as *const _));
            }
            found
        }

        /// 配置编码器输入/输出类型
        unsafe fn configure(&mut self) -> Option<()> {
            // 输入类型:BGRA32(RGB32)
            let input_type = MFCreateMediaType().ok()?;
            input_type
                .SetGUID(&MF_MT_MAJOR_TYPE as *const _, &MFMediaType_Video as *const _)
                .ok()?;
            input_type
                .SetGUID(&MF_MT_SUBTYPE as *const _, &MFVideoFormat_RGB32 as *const _)
                .ok()?;
            input_type
                .SetUINT32(&MF_MT_INTERLACE_MODE as *const _, MFVideoInterlace_Progressive.0 as u32)
                .ok()?;
            input_type
                .SetUINT32(&MF_MT_VIDEO_NOMINAL_RANGE as *const _, MFNominalRange_0_255.0 as u32)
                .ok()?;
            input_type
                .SetUINT64(&MF_MT_FRAME_SIZE as *const _, Self::pack_size(self.width, self.height))
                .ok()?;
            input_type
                .SetUINT64(&MF_MT_FRAME_RATE as *const _, Self::pack_size(self.frame_rate, 1))
                .ok()?;
            self.transform.SetInputType(0, &input_type, 0).ok()?;

            // 输出类型:根据 codec 选 H.264 或 HEVC
            let output_subtype = match self.codec {
                Codec::H264 => MFVideoFormat_H264,
                Codec::Hevc => MFVideoFormat_HEVC,
            };
            let output_type = MFCreateMediaType().ok()?;
            output_type
                .SetGUID(&MF_MT_MAJOR_TYPE as *const _, &MFMediaType_Video as *const _)
                .ok()?;
            output_type
                .SetGUID(&MF_MT_SUBTYPE as *const _, &output_subtype as *const _)
                .ok()?;
            output_type
                .SetUINT32(&MF_MT_INTERLACE_MODE as *const _, MFVideoInterlace_Progressive.0 as u32)
                .ok()?;
            output_type
                .SetUINT64(&MF_MT_FRAME_SIZE as *const _, Self::pack_size(self.width, self.height))
                .ok()?;
            output_type
                .SetUINT64(&MF_MT_FRAME_RATE as *const _, Self::pack_size(self.frame_rate, 1))
                .ok()?;
            output_type
                .SetUINT32(&MF_MT_AVG_BITRATE as *const _, self.avg_bitrate)
                .ok()?;
            self.transform.SetOutputType(0, &output_type, 0).ok()?;

            // 通知流开始
            self.transform
                .ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)
                .ok()?;
            self.transform
                .ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0)
                .ok()?;

            self.initialized = true;
            Some(())
        }

        /// 编码一帧 BGRA 像素,返回 NALU 字节
        pub fn encode_frame(&mut self, bgra: &[u8]) -> Option<Vec<u8>> {
            if !self.initialized {
                return None;
            }
            unsafe {
                let sample = MFCreateSample().ok()?;
                let buffer = MFCreateMemoryBuffer(bgra.len() as u32).ok()?;

                // 拷贝像素到 buffer
                let mut ptr: *mut u8 = std::ptr::null_mut();
                let mut max_len: u32 = 0;
                let mut cur_len: u32 = 0;
                buffer
                    .Lock(&mut ptr, Some(&mut max_len), Some(&mut cur_len))
                    .ok()?;
                if ptr.is_null() || max_len < bgra.len() as u32 {
                    let _ = buffer.Unlock();
                    return None;
                }
                std::ptr::copy_nonoverlapping(bgra.as_ptr(), ptr, bgra.len());
                let _ = buffer.SetCurrentLength(bgra.len() as u32);
                let _ = buffer.Unlock();

                sample.AddBuffer(&buffer).ok()?;

                // 时间戳(100ns 单位)
                let timestamp = self.frame_index * 10_000_000 / self.frame_rate as u64;
                let duration = 10_000_000 / self.frame_rate as u64;
                let _ = sample.SetSampleTime(timestamp as i64);
                let _ = sample.SetSampleDuration(duration as i64);
                self.frame_index += 1;

                self.transform.ProcessInput(0, &sample, 0).ok()?;
                self.collect_output()
            }
        }

        /// 收集编码器输出的 NALU(循环直到无输出)
        unsafe fn collect_output(&mut self) -> Option<Vec<u8>> {
            let mut all_nalu = Vec::new();
            loop {
                let sample = MFCreateSample().ok()?;
                let buffer_size = self.width * self.height * 4;
                let buffer = MFCreateMemoryBuffer(buffer_size).ok()?;
                sample.AddBuffer(&buffer).ok()?;

                let mut output_buf = MFT_OUTPUT_DATA_BUFFER {
                    dwStreamID: 0,
                    pSample: std::mem::ManuallyDrop::new(Some(sample)),
                    dwStatus: 0,
                    pEvents: std::mem::ManuallyDrop::new(None),
                };
                let mut status = 0u32;

                match self.transform.ProcessOutput(
                    0,
                    std::slice::from_mut(&mut output_buf),
                    &mut status,
                ) {
                    Ok(()) => {
                        if let Some(sample) =
                            std::mem::ManuallyDrop::take(&mut output_buf.pSample)
                        {
                            if let Some(nalu) = self.extract_nalu(&sample) {
                                all_nalu.extend_from_slice(&nalu);
                            }
                        }
                    }
                    Err(e) => {
                        let _ = std::mem::ManuallyDrop::take(&mut output_buf.pSample);
                        tracing::trace!("ProcessOutput 结束: {:x}", e.code().0);
                        break;
                    }
                }
            }
            if all_nalu.is_empty() {
                None
            } else {
                Some(all_nalu)
            }
        }

        unsafe fn extract_nalu(&self, sample: &IMFSample) -> Option<Vec<u8>> {
            let buffer_count = sample.GetBufferCount().ok()?;
            if buffer_count == 0 {
                return None;
            }
            let buffer = sample.GetBufferByIndex(0).ok()?;
            let mut ptr: *mut u8 = std::ptr::null_mut();
            let mut max_len: u32 = 0;
            let mut cur_len: u32 = 0;
            buffer
                .Lock(&mut ptr, Some(&mut max_len), Some(&mut cur_len))
                .ok()?;
            if ptr.is_null() || cur_len == 0 {
                let _ = buffer.Unlock();
                return None;
            }
            let data = std::slice::from_raw_parts(ptr, cur_len as usize).to_vec();
            let _ = buffer.Unlock();
            Some(data)
        }

        /// 刷新编码器(取出剩余输出)
        pub fn drain(&mut self) -> Vec<Vec<u8>> {
            if !self.initialized {
                return Vec::new();
            }
            unsafe {
                let _ = self.transform.ProcessMessage(MFT_MESSAGE_COMMAND_DRAIN, 0);
            }
            let mut outputs = Vec::new();
            loop {
                match unsafe { self.collect_output() } {
                    Some(nalu) => outputs.push(nalu),
                    None => break,
                }
            }
            unsafe {
                let _ = self.transform.ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
                let _ = self.transform.ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
            }
            self.initialized = false;
            outputs
        }

        fn pack_size(a: u32, b: u32) -> u64 {
            ((a as u64) << 32) | (b as u64)
        }
    }

    impl Drop for MFEncoder {
        fn drop(&mut self) {
            if self.initialized {
                let _ = self.drain();
            }
        }
    }

    // ── 全局编码器单例(按 codec+分辨率缓存) ──
    struct EncoderEntry {
        encoder: Mutex<MFEncoder>,
        codec: Codec,
        width: u32,
        height: u32,
    }

    static ENCODER: OnceLock<Mutex<Option<EncoderEntry>>> = OnceLock::new();

    /// 用指定 codec 编码一帧(使用全局缓存的编码器)
    pub fn encode_frame_with_codec(
        bgra: &[u8],
        width: u32,
        height: u32,
        frame_rate: u32,
        avg_bitrate: u32,
        codec: Codec,
    ) -> Option<Vec<u8>> {
        let mutex = ENCODER.get_or_init(|| Mutex::new(None));
        let mut guard = mutex.lock().ok()?;

        // 检查是否需要重建编码器(codec 或分辨率变化,或上次创建失败 guard=None)
        let need_rebuild = match guard.as_ref() {
            Some(entry) => entry.codec != codec || entry.width != width || entry.height != height,
            None => true,
        };

        if need_rebuild {
            let new_encoder = MFEncoder::new(codec, width, height, frame_rate, avg_bitrate);
            if new_encoder.is_none() {
                tracing::error!(
                    "MFEncoder::new 失败: codec={:?} {}x{} bitrate={}",
                    codec, width, height, avg_bitrate
                );
            } else {
                tracing::info!(
                    "MFEncoder 创建成功: codec={:?} {}x{} bitrate={}",
                    codec, width, height, avg_bitrate
                );
            }
            *guard = new_encoder.map(|enc| {
                EncoderEntry {
                    encoder: Mutex::new(enc),
                    codec,
                    width,
                    height,
                }
            });
        }

        let entry = guard.as_ref()?;
        let mut enc_guard = entry.encoder.lock().ok()?;
        enc_guard.encode_frame(bgra)
    }

    /// 探测指定 codec 在当前硬件上是否可用(尝试枚举编码器 MFT)
    ///
    /// 返回 true 表示可以创建该 codec 的编码器(硬件或软件)。
    /// HEVC 在大多数消费级 Windows 上需要 NVENC/AMF/QSV HEVC 硬件支持,
    /// 否则返回 false,调用方应回退到 H.264。
    pub fn probe_codec_support(codec: Codec) -> bool {
        unsafe {
            // 初始化 MF(如果尚未初始化)
            static MF_STARTED: OnceLock<()> = OnceLock::new();
            MF_STARTED.get_or_init(|| {
                let _ = MFStartup(MF_API_VERSION, MFSTARTUP_LITE);
            });

            let subtype = match codec {
                Codec::H264 => MFVideoFormat_H264,
                Codec::Hevc => MFVideoFormat_HEVC,
            };
            let output_type_info = MFT_REGISTER_TYPE_INFO {
                guidMajorType: MFMediaType_Video,
                guidSubtype: subtype,
            };

            // 优先硬件
            let hw_flags = MFT_ENUM_FLAG(MFT_ENUM_FLAG_HARDWARE.0 | MFT_ENUM_FLAG_SYNCMFT.0);
            if MFEncoder::enum_with_flags(hw_flags, &output_type_info).is_some() {
                return true;
            }
            // 软件回退
            MFEncoder::enum_with_flags(MFT_ENUM_FLAG_SYNCMFT, &output_type_info).is_some()
        }
    }

    /// 刷新编码器,取出剩余 NALU
    pub fn drain_encoder() -> Vec<Vec<u8>> {
        let mutex = match ENCODER.get() {
            Some(m) => m,
            None => return Vec::new(),
        };
        let mut guard = match mutex.lock() {
            Ok(g) => g,
            Err(_) => return Vec::new(),
        };
        if let Some(entry) = guard.as_mut() {
            let mut enc_guard = match entry.encoder.lock() {
                Ok(g) => g,
                Err(_) => return Vec::new(),
            };
            enc_guard.drain()
        } else {
            Vec::new()
        }
    }
}

#[cfg(windows)]
pub use mf_encoder::{drain_encoder, encode_frame_with_codec, probe_codec_support};

/// H.264 编码便捷函数(向后兼容)
#[cfg(windows)]
pub fn encode_frame(
    bgra: &[u8],
    width: u32,
    height: u32,
    frame_rate: u32,
    avg_bitrate: u32,
) -> Option<Vec<u8>> {
    encode_frame_with_codec(bgra, width, height, frame_rate, avg_bitrate, Codec::H264)
}

#[cfg(not(windows))]
pub fn encode_frame_with_codec(
    _bgra: &[u8],
    _width: u32,
    _height: u32,
    _frame_rate: u32,
    _avg_bitrate: u32,
    _codec: Codec,
) -> Option<Vec<u8>> {
    None
}

#[cfg(not(windows))]
pub fn probe_codec_support(_codec: Codec) -> bool {
    false
}

#[cfg(not(windows))]
pub fn encode_frame(
    _bgra: &[u8],
    _width: u32,
    _height: u32,
    _frame_rate: u32,
    _avg_bitrate: u32,
) -> Option<Vec<u8>> {
    None
}

#[cfg(not(windows))]
pub fn drain_encoder() -> Vec<Vec<u8>> {
    Vec::new()
}
