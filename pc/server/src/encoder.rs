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
        MFNominalRange_0_255, MFVideoFormat_H264, MFVideoFormat_HEVC, MFVideoFormat_NV12,
        MFVideoFormat_RGB32, MFVideoInterlace_Progressive, MFTEnumEx, MFT_ENUM_FLAG,
        MFT_ENUM_FLAG_HARDWARE,
        MFT_ENUM_FLAG_SYNCMFT, MFT_MESSAGE_COMMAND_DRAIN, MFT_MESSAGE_NOTIFY_BEGIN_STREAMING,
        MFT_MESSAGE_NOTIFY_END_OF_STREAM, MFT_MESSAGE_NOTIFY_END_STREAMING,
        MFT_MESSAGE_NOTIFY_START_OF_STREAM, MFT_OUTPUT_DATA_BUFFER, MFT_REGISTER_TYPE_INFO,
        MFT_CATEGORY_VIDEO_ENCODER,
    };
    use windows::Win32::System::Com::CoTaskMemFree;

    use super::Codec;

    /// 编码器输入格式(NV12 优先,RGB32 回退)
    /// 不同的编码器 MFT 支持的输入格式不同:
    /// - 硬件编码器(NVENC/AMF/QSV):通常只接受 NV12
    /// - 软件 H.264 编码器:接受 NV12 和 RGB32
    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    enum InputFormat {
        /// NV12(YUV 4:2:0 半平面,硬件编码器首选)
        Nv12,
        /// RGB32(BGRA,软件编码器回退)
        Rgb32,
    }

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
        /// 实际配置成功的输入格式(决定 encode_frame 的转换路径)
        input_format: InputFormat,
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
                    let r = MFStartup(MF_API_VERSION, MFSTARTUP_LITE);
                    let code = match &r {
                        Ok(()) => 0i32,
                        Err(e) => e.code().0,
                    };
                    tracing::info!("MFStartup 返回 0x{:x}", code as u32);
                });

                // 尝试硬件编码器,configure 失败则回退软件编码器
                // 原因:硬件 H.264 编码器(Intel QSV / NVIDIA NVENC)通常不接受 RGB32 输入,
                // SetInputType 返回 MF_E_INVALIDMEDIATYPE (0xc00d6d77)。
                // 软件编码器接受 RGB32,性能足够 1080p30fps。
                let candidates = Self::enum_encoders_all(codec);
                for (idx, transform) in candidates.iter().enumerate() {
                    let mut encoder = Self {
                        transform: transform.clone(),
                        codec,
                        width,
                        height,
                        frame_rate,
                        avg_bitrate,
                        frame_index: 0,
                        initialized: false,
                        input_format: InputFormat::Nv12,
                    };
                    match encoder.configure() {
                        Some(_) => {
                            tracing::info!(
                                "MFEncoder::new: 使用 encoder[{}] 成功 codec={:?} {}x{}",
                                idx, codec, width, height
                            );
                            return Some(encoder);
                        }
                        None => {
                            tracing::warn!(
                                "MFEncoder::new: encoder[{}] configure 失败,尝试下一个",
                                idx
                            );
                        }
                    }
                }
                tracing::error!("MFEncoder::new: 所有 {} 个编码器都 configure 失败", candidates.len());
                None
            }
        }

        /// 返回使用的 codec
        pub fn codec(&self) -> Codec {
            self.codec
        }

        /// 枚举所有可用编码器 MFT(硬件优先,软件兜底)
        /// 返回候选列表,由调用方逐个尝试 configure 直到成功
        unsafe fn enum_encoders_all(codec: Codec) -> Vec<IMFTransform> {
            let subtype = match codec {
                Codec::H264 => MFVideoFormat_H264,
                Codec::Hevc => MFVideoFormat_HEVC,
            };
            let output_type_info = MFT_REGISTER_TYPE_INFO {
                guidMajorType: MFMediaType_Video,
                guidSubtype: subtype,
            };

            let mut all = Vec::new();

            // 优先硬件编码器
            let hw_flags = MFT_ENUM_FLAG(MFT_ENUM_FLAG_HARDWARE.0 | MFT_ENUM_FLAG_SYNCMFT.0);
            all.extend(Self::enum_with_flags_all(hw_flags, &output_type_info));

            // 软件编码器兜底(HEVC 软件编码器在大多数 Windows 上不存在)
            all.extend(Self::enum_with_flags_all(MFT_ENUM_FLAG_SYNCMFT, &output_type_info));

            all
        }

        /// 用指定 flags 枚举并激活所有可用的编码器 MFT
        unsafe fn enum_with_flags_all(
            flags: MFT_ENUM_FLAG,
            output_type_info: &MFT_REGISTER_TYPE_INFO,
        ) -> Vec<IMFTransform> {
            use windows::Win32::Media::MediaFoundation::IMFActivate;
            let mut activates_ptr: *mut Option<IMFActivate> = std::ptr::null_mut();
            let mut count: u32 = 0;

            let hr = MFTEnumEx(
                MFT_CATEGORY_VIDEO_ENCODER,
                flags,
                None,
                Some(output_type_info as *const _),
                &mut activates_ptr,
                &mut count,
            );
            let hr_code = match &hr {
                Ok(()) => 0i32,
                Err(e) => e.code().0,
            };
            tracing::info!(
                "enum_with_flags_all: MFTEnumEx hr=0x{:x} count={} flags={}",
                hr_code as u32, count, flags.0
            );

            let mut found = Vec::new();
            if hr.is_ok() && count > 0 && !activates_ptr.is_null() {
                let activates = std::slice::from_raw_parts(activates_ptr, count as usize);
                for (i, activate) in activates.iter().enumerate() {
                    if let Some(ref act) = activate {
                        match act.ActivateObject::<IMFTransform>() {
                            Ok(transform) => {
                                tracing::info!("enum_with_flags_all: 激活 encoder[{}] 成功", i);
                                found.push(transform);
                            }
                            Err(e) => {
                                tracing::warn!("enum_with_flags_all: 激活 encoder[{}] 失败: 0x{:x}", i, e.code().0);
                            }
                        }
                    }
                }
            }
            if !activates_ptr.is_null() {
                CoTaskMemFree(Some(activates_ptr as *const _));
            }
            found
        }

        /// 尝试设置指定的输入格式,成功返回 Some(())
        unsafe fn try_set_input_type(&self, fmt: InputFormat) -> Option<()> {
            let subtype = match fmt {
                InputFormat::Nv12 => MFVideoFormat_NV12,
                InputFormat::Rgb32 => MFVideoFormat_RGB32,
            };
            let input_type = MFCreateMediaType().ok()?;
            input_type
                .SetGUID(&MF_MT_MAJOR_TYPE as *const _, &MFMediaType_Video as *const _)
                .ok()?;
            input_type
                .SetGUID(&MF_MT_SUBTYPE as *const _, &subtype as *const _)
                .ok()?;
            input_type
                .SetUINT32(&MF_MT_INTERLACE_MODE as *const _, MFVideoInterlace_Progressive.0 as u32)
                .ok()?;
            // 声明 full range [0,255](NV12 模式与 bgra_to_nv12 的 BT.601 full-range 匹配;
            // RGB32 模式下编码器也需要知道 range,否则画面偏暗)
            input_type
                .SetUINT32(&MF_MT_VIDEO_NOMINAL_RANGE as *const _, MFNominalRange_0_255.0 as u32)
                .ok()?;
            input_type
                .SetUINT64(&MF_MT_FRAME_SIZE as *const _, Self::pack_size(self.width, self.height))
                .ok()?;
            input_type
                .SetUINT64(&MF_MT_FRAME_RATE as *const _, Self::pack_size(self.frame_rate, 1))
                .ok()?;
            match self.transform.SetInputType(0, &input_type, 0) {
                Ok(()) => Some(()),
                Err(e) => {
                    tracing::warn!(
                        "try_set_input_type: {:?} SetInputType 失败: 0x{:x} ({}x{})",
                        fmt, e.code().0, self.width, self.height
                    );
                    None
                }
            }
        }

        /// 配置编码器输入/输出类型
        ///
        /// 输入格式尝试顺序:NV12(硬件编码器首选)→ RGB32(软件编码器回退)
        /// 不同编码器 MFT 支持的输入格式不同,逐个尝试直到 SetInputType 成功。
        unsafe fn configure(&mut self) -> Option<()> {
            // 尝试多种输入格式:NV12 → RGB32
            let formats = [InputFormat::Nv12, InputFormat::Rgb32];
            let mut configured_format = None;
            for fmt in &formats {
                match self.try_set_input_type(*fmt) {
                    Some(()) => {
                        configured_format = Some(*fmt);
                        break;
                    }
                    None => continue,
                }
            }
            let input_format = configured_format?;
            self.input_format = input_format;
            tracing::info!("configure: 输入格式 = {:?}", input_format);

            // 输出类型:根据 codec 选 H.264 或 HEVC
            let output_subtype = match self.codec {
                Codec::H264 => MFVideoFormat_H264,
                Codec::Hevc => MFVideoFormat_HEVC,
            };
            let output_type = MFCreateMediaType().ok()?;
            let mut step = || -> Option<()> {
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
                Some(())
            };
            if step().is_none() {
                tracing::error!("configure: 输出类型设置失败 (output_type 字段)");
                return None;
            }
            match self.transform.SetOutputType(0, &output_type, 0) {
                Ok(()) => {}
                Err(e) => {
                    tracing::error!("configure: SetOutputType 失败: 0x{:x}", e.code().0);
                    return None;
                }
            }

            // 通知流开始
            match self.transform.ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0) {
                Ok(()) => {}
                Err(e) => {
                    tracing::error!("configure: BEGIN_STREAMING 失败: 0x{:x}", e.code().0);
                    return None;
                }
            }
            match self.transform.ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0) {
                Ok(()) => {}
                Err(e) => {
                    tracing::error!("configure: START_OF_STREAM 失败: 0x{:x}", e.code().0);
                    return None;
                }
            }

            self.initialized = true;
            tracing::info!(
                "configure: 成功 codec={:?} {}x{} fps={} bitrate={}",
                self.codec, self.width, self.height, self.frame_rate, self.avg_bitrate
            );
            Some(())
        }

        /// 编码一帧 BGRA 像素,返回 NALU 字节
        ///
        /// 根据 configure 阶段确定的 input_format 决定转换路径:
        /// - Nv12:先 bgra_to_nv12 转换再喂给编码器
        /// - Rgb32:直接喂 BGRA 数据(软件编码器接受)
        pub fn encode_frame(&mut self, bgra: &[u8]) -> Option<Vec<u8>> {
            if !self.initialized {
                if self.frame_index == 0 {
                    tracing::error!("encode_frame: 编码器未初始化 (initialized=false)");
                }
                return None;
            }
            let expected_bgra = (self.width as usize) * (self.height as usize) * 4;
            if bgra.len() < expected_bgra {
                if self.frame_index == 0 {
                    tracing::error!(
                        "encode_frame: bgra 长度不足 {} < {} ({}x{})",
                        bgra.len(), expected_bgra, self.width, self.height
                    );
                }
                return None;
            }

            // 根据输入格式选择数据源:NV12 需要转换,RGB32 直接用 BGRA
            let (frame_data, data_label) = match self.input_format {
                InputFormat::Nv12 => {
                    let nv12 = bgra_to_nv12(bgra, self.width as usize, self.height as usize);
                    (nv12, "nv12")
                }
                InputFormat::Rgb32 => (bgra.to_vec(), "bgra"),
            };
            let data_len = frame_data.len();
            unsafe {
                let sample = MFCreateSample().ok()?;
                let buffer = MFCreateMemoryBuffer(data_len as u32).ok()?;

                let mut ptr: *mut u8 = std::ptr::null_mut();
                let mut max_len: u32 = 0;
                let mut cur_len: u32 = 0;
                buffer
                    .Lock(&mut ptr, Some(&mut max_len), Some(&mut cur_len))
                    .ok()?;
                if ptr.is_null() || max_len < data_len as u32 {
                    let _ = buffer.Unlock();
                    if self.frame_index == 0 {
                        tracing::error!("encode_frame: buffer 无效 ptr={} max_len={} need={}", ptr.is_null(), max_len, data_len);
                    }
                    return None;
                }
                std::ptr::copy_nonoverlapping(frame_data.as_ptr(), ptr, data_len);
                let _ = buffer.SetCurrentLength(data_len as u32);
                let _ = buffer.Unlock();

                sample.AddBuffer(&buffer).ok()?;

                let timestamp = self.frame_index * 10_000_000 / self.frame_rate as u64;
                let duration = 10_000_000 / self.frame_rate as u64;
                let _ = sample.SetSampleTime(timestamp as i64);
                let _ = sample.SetSampleDuration(duration as i64);

                if self.transform.ProcessInput(0, &sample, 0).is_err() {
                    if self.frame_index == 0 {
                        tracing::error!("encode_frame: ProcessInput 失败 frame_index={}", self.frame_index);
                    }
                    self.frame_index += 1;
                    return None;
                }

                let need_debug = self.frame_index < 3;
                if need_debug {
                    tracing::info!("encode_frame: ProcessInput 成功 frame_index={} {}_len={}", self.frame_index, data_label, data_len);
                }
                self.frame_index += 1;

                let result = self.collect_output();
                if need_debug {
                    tracing::info!("encode_frame: collect_output 返回 {} 字节 frame_index={}", result.as_ref().map(|v| v.len()).unwrap_or(0), self.frame_index);
                }
                result
            }
        }

        /// 收集编码器输出的 NALU(循环直到无输出)
        unsafe fn collect_output(&mut self) -> Option<Vec<u8>> {
            let mut all_nalu = Vec::new();
            let mut loop_count = 0u32;
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
                        loop_count += 1;
                    }
                    Err(e) => {
                        let _ = std::mem::ManuallyDrop::take(&mut output_buf.pSample);
                        // 只在前几帧打印错误码,0xc00d6d72 = MF_E_TRANSFORM_NEED_MORE_INPUT(正常,编码器需要更多输入)
                        if self.frame_index < 3 {
                            tracing::info!(
                                "collect_output: ProcessOutput 返回 0x{:x} (loop_count={} frame_index={})",
                                e.code().0, loop_count, self.frame_index
                            );
                        }
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

    /// BGRA → NV12 转换(BT.601 full range,与 input_type 的 MFNominalRange_0_255 匹配)
    ///
    /// NV12 内存布局:
    /// - Y 平面:height 行,每行 width 字节(stride = width)
    /// - UV 交错平面:height/2 行,每行 width 字节(U V 交错,stride = width)
    ///
    /// 总大小 = width * height * 3 / 2
    ///
    /// full range BT.601 整数运算(避免浮点):
    /// - Y = (77*R + 150*G + 29*B + 128) >> 8
    /// - U = ((-43*R - 84*G + 127*B + 128) >> 8) + 128
    /// - V = ((127*R - 106*G - 21*B + 128) >> 8) + 128
    ///
    /// UV 在 2x2 像素块内做平均下采样(每 4 个像素共享一对 UV)
    fn bgra_to_nv12(bgra: &[u8], width: usize, height: usize) -> Vec<u8> {
        debug_assert!(width % 2 == 0 && height % 2 == 0, "NV12 要求宽高为偶数");
        let y_size = width * height;
        let uv_size = width * (height / 2);
        let mut nv12 = vec![0u8; y_size + uv_size];

        // 拆分 Y / UV 平面(一次性 split_at_mut 避免重复借用)
        let (y_plane, uv_plane) = nv12.split_at_mut(y_size);

        // Y 平面(逐像素)
        for row in 0..height {
            let y_row = row * width;
            let bgra_row = y_row * 4;
            for col in 0..width {
                let i = bgra_row + col * 4;
                let b = bgra[i] as i32;
                let g = bgra[i + 1] as i32;
                let r = bgra[i + 2] as i32;
                let y = (77 * r + 150 * g + 29 * b + 128) >> 8;
                y_plane[y_row + col] = y.clamp(0, 255) as u8;
            }
        }

        // UV 交错平面(2x2 块平均下采样)
        let half_h = height / 2;
        let half_w = width / 2;
        for row in 0..half_h {
            let uv_row = row * width; // UV 行 stride = width
            let py = row * 2;         // 对应 Y 平面行
            for col in 0..half_w {
                let px = col * 2;
                let i00 = (py * width + px) * 4;
                let i01 = (py * width + (px + 1)) * 4;
                let i10 = ((py + 1) * width + px) * 4;
                let i11 = ((py + 1) * width + (px + 1)) * 4;

                // 2x2 块的 B/G/R 平均
                let b = (bgra[i00] as i32 + bgra[i01] as i32
                    + bgra[i10] as i32 + bgra[i11] as i32) >> 2;
                let g = (bgra[i00 + 1] as i32 + bgra[i01 + 1] as i32
                    + bgra[i10 + 1] as i32 + bgra[i11 + 1] as i32) >> 2;
                let r = (bgra[i00 + 2] as i32 + bgra[i01 + 2] as i32
                    + bgra[i10 + 2] as i32 + bgra[i11 + 2] as i32) >> 2;

                let u = (((-43 * r - 84 * g + 127 * b + 128) >> 8) + 128).clamp(0, 255) as u8;
                let v = (((127 * r - 106 * g - 21 * b + 128) >> 8) + 128).clamp(0, 255) as u8;

                let uv_idx = uv_row + col * 2;
                uv_plane[uv_idx] = u;
                uv_plane[uv_idx + 1] = v;
            }
        }

        nv12
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

            // 枚举硬件+软件,任一可用即支持
            !MFEncoder::enum_encoders_all(codec).is_empty()
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
