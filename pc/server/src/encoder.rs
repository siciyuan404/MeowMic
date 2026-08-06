//! H.264 硬件编码器(Media Foundation)
//!
//! 使用 Windows Media Foundation 的 H.264 编码器 MFT,自动选择最佳硬件编码器:
//! - NVIDIA NVENC(GeForce 显卡)
//! - AMD AMF(Radeon 显卡)
//! - Intel QuickSync(核显)
//! - 软件 fallback(无 GPU 时)
//!
//! 输入: BGRA32 像素
//! 输出: H.264 NALU 字节流(Annex-B 格式,带起始码 00 00 00 01)
//!
//! 端点:
//! - 编码单帧:encode_frame() -> Vec<u8> (NALU)
//! - 编码流:持续编码,通过 /screen/stream 端点分块传输

#[cfg(windows)]
mod mf_encoder {
    use std::sync::{Mutex, OnceLock};

    use windows::core::Interface;
    use windows::Win32::Media::MediaFoundation::{
        MFCreateMemoryBuffer, MFCreateMediaType, MFCreateSample, MFStartup,
        IMFSample, IMFTransform, MF_API_VERSION, MF_MT_AVG_BITRATE,
        MF_MT_FRAME_RATE, MF_MT_FRAME_SIZE, MF_MT_INTERLACE_MODE, MF_MT_MAJOR_TYPE,
        MF_MT_SUBTYPE, MF_MT_VIDEO_NOMINAL_RANGE, MFSTARTUP_LITE, MFMediaType_Video,
        MFNominalRange_0_255, MFVideoFormat_H264, MFVideoFormat_RGB32,
        MFVideoInterlace_Progressive, MFTEnumEx, MFT_ENUM_FLAG, MFT_ENUM_FLAG_HARDWARE,
        MFT_ENUM_FLAG_SYNCMFT, MFT_MESSAGE_COMMAND_DRAIN, MFT_MESSAGE_NOTIFY_BEGIN_STREAMING,
        MFT_MESSAGE_NOTIFY_END_OF_STREAM, MFT_MESSAGE_NOTIFY_END_STREAMING,
        MFT_MESSAGE_NOTIFY_START_OF_STREAM, MFT_OUTPUT_DATA_BUFFER, MFT_REGISTER_TYPE_INFO,
        MFT_CATEGORY_VIDEO_ENCODER,
    };
    use windows::Win32::System::Com::CoTaskMemFree;

    /// H.264 编码器封装
    pub struct H264Encoder {
        transform: IMFTransform,
        width: u32,
        height: u32,
        frame_rate: u32,
        avg_bitrate: u32,
        frame_index: u64,
        initialized: bool,
    }

    unsafe impl Send for H264Encoder {}

    impl H264Encoder {
        /// 创建 H.264 硬件编码器
        pub fn new(width: u32, height: u32, frame_rate: u32, avg_bitrate: u32) -> Option<Self> {
            unsafe {
                // 初始化 MF(如果尚未初始化)
                static MF_STARTED: OnceLock<()> = OnceLock::new();
                MF_STARTED.get_or_init(|| {
                    let _ = MFStartup(MF_API_VERSION, MFSTARTUP_LITE);
                });

                let transform = Self::enum_h264_encoder()?;
                let mut encoder = Self {
                    transform,
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

        /// 枚举 H.264 编码器 MFT(优先硬件,回退软件)
        unsafe fn enum_h264_encoder() -> Option<IMFTransform> {
            // 输出类型过滤:Video / H264
            let output_type_info = MFT_REGISTER_TYPE_INFO {
                guidMajorType: MFMediaType_Video,
                guidSubtype: MFVideoFormat_H264,
            };

            // 优先硬件编码器
            let hw_flags = MFT_ENUM_FLAG(MFT_ENUM_FLAG_HARDWARE.0 | MFT_ENUM_FLAG_SYNCMFT.0);
            if let Some(transform) = Self::enum_with_flags(hw_flags, &output_type_info) {
                return Some(transform);
            }

            // 回退到软件编码器
            Self::enum_with_flags(MFT_ENUM_FLAG_SYNCMFT, &output_type_info)
        }

        /// 用指定 flags 枚举并激活第一个可用的 H.264 编码器 MFT
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
            // 输入类型:BGRA32(RGB32) — IMFMediaType 继承自 IMFAttributes,可直接调用 SetGUID/SetUINT32
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

            // 输出类型:H.264
            let output_type = MFCreateMediaType().ok()?;
            output_type
                .SetGUID(&MF_MT_MAJOR_TYPE as *const _, &MFMediaType_Video as *const _)
                .ok()?;
            output_type
                .SetGUID(&MF_MT_SUBTYPE as *const _, &MFVideoFormat_H264 as *const _)
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

        /// 编码一帧 BGRA 像素,返回 H.264 NALU 字节
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
                // 创建输出 sample(预分配缓冲区)
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
                        // 取回 sample(从 ManuallyDrop 中)
                        if let Some(sample) =
                            std::mem::ManuallyDrop::take(&mut output_buf.pSample)
                        {
                            if let Some(nalu) = self.extract_nalu(&sample) {
                                all_nalu.extend_from_slice(&nalu);
                            }
                        }
                    }
                    Err(e) => {
                        // MF_E_TRANSFORM_NEED_MORE_INPUT 表示需要更多输入,属正常
                        // 释放未取走的 sample(避免泄漏)
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

        /// 从 sample 中提取 NALU 数据
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

    impl Drop for H264Encoder {
        fn drop(&mut self) {
            if self.initialized {
                let _ = self.drain();
            }
        }
    }

    // ── 全局编码器单例(按分辨率缓存) ──
    struct EncoderEntry {
        encoder: Mutex<H264Encoder>,
        width: u32,
        height: u32,
    }

    static ENCODER: OnceLock<Mutex<Option<EncoderEntry>>> = OnceLock::new();

    /// 编码一帧(使用全局缓存的编码器)
    pub fn encode_frame(
        bgra: &[u8],
        width: u32,
        height: u32,
        frame_rate: u32,
        avg_bitrate: u32,
    ) -> Option<Vec<u8>> {
        let mutex = ENCODER.get_or_init(|| Mutex::new(None));
        let mut guard = mutex.lock().ok()?;

        // 检查是否需要重建编码器(分辨率变化)
        let need_rebuild = match guard.as_ref() {
            Some(entry) => entry.width != width || entry.height != height,
            None => true,
        };

        if need_rebuild {
            *guard = H264Encoder::new(width, height, frame_rate, avg_bitrate).map(|enc| {
                EncoderEntry {
                    encoder: Mutex::new(enc),
                    width,
                    height,
                }
            });
        }

        // 拆分借用:避免 guard.as_ref()?.encoder.lock().ok()?.encode_frame(bgra) 的临时借用生命周期问题
        let entry = guard.as_ref()?;
        let mut enc_guard = entry.encoder.lock().ok()?;
        enc_guard.encode_frame(bgra)
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
pub use mf_encoder::{drain_encoder, encode_frame};

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
