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
        MFCreateMemoryBuffer, MFCreateMediaType, MFCreateSample, MFStartup, IMFMediaType,
        IMFSample, IMFTransform, MF_API_VERSION, MF_MT_AVG_BITRATE, MF_MT_FRAME_RATE,
        MF_MT_FRAME_SIZE, MF_MT_INTERLACE_MODE, MF_MT_MAJOR_TYPE, MF_MT_SUBTYPE,
        MF_MT_VIDEO_NOMINAL_RANGE, MF_MT_MPEG2_PROFILE, MFSTARTUP_LITE, MFMediaType_Video,
        MFNominalRange_0_255, MFVideoFormat_H264, MFVideoFormat_HEVC, MFVideoFormat_NV12,
        MFVideoFormat_RGB32, MFVideoInterlace_Progressive, MFTEnumEx, MFT_ENUM_FLAG,
        MFT_ENUM_FLAG_ASYNCMFT, MFT_ENUM_FLAG_HARDWARE, MFT_ENUM_FLAG_SYNCMFT,
        MFT_INPUT_STATUS_ACCEPT_DATA, MFT_MESSAGE_COMMAND_DRAIN,
        MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, MFT_MESSAGE_NOTIFY_END_OF_STREAM,
        MFT_MESSAGE_NOTIFY_END_STREAMING, MFT_MESSAGE_NOTIFY_START_OF_STREAM,
        MFT_OUTPUT_DATA_BUFFER, MFT_REGISTER_TYPE_INFO, MFT_CATEGORY_VIDEO_ENCODER,
    };
    use windows::Win32::System::Com::CoTaskMemFree;

    use super::Codec;

    /// 编码器输入格式
    /// 不同的编码器 MFT 支持的输入格式不同,按优先级尝试:
    /// - IYUV/I420(YUV 4:2:0 planar,软件编码器首选,MFWebCamRtp 示例使用)
    /// - NV12(YUV 4:2:0 半平面,硬件编码器首选)
    /// - YV12(YUV 4:2:0 planar,Y+V+U 顺序)
    /// - YUYV/YUY2(YUV 4:2:2 packed)
    /// - RGB32(BGRA,软件编码器回退)
    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    enum InputFormat {
        /// IYUV/I420(YUV 4:2:0 planar,软件编码器首选)
        Iyuv,
        /// NV12(YUV 4:2:0 半平面,硬件编码器首选)
        Nv12,
        /// YV12(YUV 4:2:0 planar,Y+V+U 顺序)
        Yv12,
        /// YUYV/YUY2(YUV 4:2:2 packed)
        Yuyv,
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
        /// 是否为异步 MFT(支持 IMFMediaEventGenerator)
        is_async: bool,
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
                static MF_STARTED: OnceLock<()> = OnceLock::new();
                MF_STARTED.get_or_init(|| {
                    let r = MFStartup(MF_API_VERSION, MFSTARTUP_LITE);
                    let code = match &r {
                        Ok(()) => 0i32,
                        Err(e) => e.code().0,
                    };
                    tracing::info!("MFStartup 返回 0x{:x}", code as u32);
                });

                let candidates = Self::enum_encoders_all(codec);
                tracing::info!(
                    "MFEncoder::new: 枚举到 {} 个编码器候选 codec={:?}",
                    candidates.len(),
                    codec
                );

                for (idx, transform) in candidates.iter().enumerate() {
                    let is_async = Self::check_async_mft(transform);
                    tracing::info!(
                        "MFEncoder::new: encoder[{}] is_async={}",
                        idx, is_async
                    );

                    let mut encoder = Self {
                        transform: transform.clone(),
                        codec,
                        width,
                        height,
                        frame_rate,
                        avg_bitrate,
                        frame_index: 0,
                        initialized: false,
                        is_async,
                        input_format: InputFormat::Iyuv,
                    };
                    match encoder.configure() {
                        Some(_) => {
                            tracing::info!(
                                "MFEncoder::new: 使用 encoder[{}] 成功 codec={:?} {}x{} is_async={}",
                                idx, codec, width, height, is_async
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
                tracing::error!(
                    "MFEncoder::new: 所有 {} 个编码器都 configure 失败",
                    candidates.len()
                );
                None
            }
        }

        /// 检查 MFT 是否为异步 MFT
        /// 异步 MFT 实现了 IMFMediaEventGenerator 接口
        unsafe fn check_async_mft(transform: &IMFTransform) -> bool {
            use windows::Win32::Media::MediaFoundation::IMFMediaEventGenerator;
            transform.cast::<IMFMediaEventGenerator>().is_ok()
        }

        pub fn codec(&self) -> Codec {
            self.codec
        }

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

            let hw_flags = MFT_ENUM_FLAG(
                MFT_ENUM_FLAG_HARDWARE.0 | MFT_ENUM_FLAG_SYNCMFT.0 | MFT_ENUM_FLAG_ASYNCMFT.0,
            );
            all.extend(Self::enum_with_flags_all(hw_flags, &output_type_info));

            all.extend(Self::enum_with_flags_all(MFT_ENUM_FLAG_SYNCMFT, &output_type_info));

            all
        }

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
                hr_code as u32,
                count,
                flags.0
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
                                tracing::warn!(
                                    "enum_with_flags_all: 激活 encoder[{}] 失败: 0x{:x}",
                                    i,
                                    e.code().0
                                );
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

        fn format_to_subtype(fmt: InputFormat) -> windows::core::GUID {
            const STD_DATA2: u16 = 0x0010;
            const STD_DATA3: u16 = 0x8000;
            const STD_DATA4: [u8; 8] = [0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71, 0x00, 0x00];
            match fmt {
                InputFormat::Nv12 => MFVideoFormat_NV12,
                InputFormat::Rgb32 => MFVideoFormat_RGB32,
                InputFormat::Iyuv => windows::core::GUID {
                    data1: 0x56555949,
                    data2: STD_DATA2,
                    data3: STD_DATA3,
                    data4: STD_DATA4,
                },
                InputFormat::Yv12 => windows::core::GUID {
                    data1: 0x32315659,
                    data2: STD_DATA2,
                    data3: STD_DATA3,
                    data4: STD_DATA4,
                },
                InputFormat::Yuyv => windows::core::GUID {
                    data1: 0x32595559,
                    data2: STD_DATA2,
                    data3: STD_DATA3,
                    data4: STD_DATA4,
                },
            }
        }

        /// 构建基础 input media type(参考 MFWebCamRtp)
        /// 包含:major type = Video, subtype, frame size, frame rate, interlace mode, nominal range
        unsafe fn build_base_type(&self, subtype: windows::core::GUID) -> Option<IMFMediaType> {
            let mt = MFCreateMediaType().ok()?;
            mt.SetGUID(
                &MF_MT_MAJOR_TYPE as *const _,
                &MFMediaType_Video as *const _,
            )
            .ok()?;
            mt.SetGUID(&MF_MT_SUBTYPE as *const _, &subtype as *const _)
                .ok()?;
            mt.SetUINT32(
                &MF_MT_INTERLACE_MODE as *const _,
                MFVideoInterlace_Progressive.0 as u32,
            )
            .ok()?;
            mt.SetUINT32(
                &MF_MT_VIDEO_NOMINAL_RANGE as *const _,
                MFNominalRange_0_255.0 as u32,
            )
            .ok()?;
            mt.SetUINT64(
                &MF_MT_FRAME_SIZE as *const _,
                Self::pack_size(self.width, self.height),
            )
            .ok()?;
            mt.SetUINT64(
                &MF_MT_FRAME_RATE as *const _,
                Self::pack_size(self.frame_rate, 1),
            )
            .ok()?;
            Some(mt)
        }

        /// 构建并设置 input type(使用 build_base_type 构造基础类型)
        unsafe fn try_set_input_type(&self, fmt: InputFormat) -> Option<()> {
            let subtype = Self::format_to_subtype(fmt);
            let input_type = self.build_base_type(subtype)?;
            match self.transform.SetInputType(0, &input_type, 0) {
                Ok(()) => Some(()),
                Err(e) => {
                    tracing::warn!(
                        "try_set_input_type: {:?} SetInputType 失败: 0x{:x} ({}x{})",
                        fmt,
                        e.code().0,
                        self.width,
                        self.height
                    );
                    None
                }
            }
        }

        /// 枚举可用的 input type(GetInputAvailableType),失败则直接构造
        /// 参考 MFWebCamRtp,优先尝试 IYUV
        unsafe fn negotiate_input_type(&self) -> Option<InputFormat> {
            for i in 0..32u32 {
                let available = match self.transform.GetInputAvailableType(0, i) {
                    Ok(mt) => mt,
                    Err(e) => {
                        if i == 0 {
                            tracing::warn!(
                                "negotiate_input_type: GetInputAvailableType[0] 失败: 0x{:x} 改用直接构造格式",
                                e.code().0
                            );
                        }
                        break;
                    }
                };
                let subtype = match available.GetGUID(&MF_MT_SUBTYPE as *const _) {
                    Ok(g) => g,
                    Err(_) => continue,
                };
                let fmt = match Self::guid_to_format(&subtype) {
                    Some(f) => f,
                    None => {
                        tracing::debug!(
                            "negotiate_input_type: 跳过未知格式[{}] GUID={:08x}",
                            i,
                            subtype.data1
                        );
                        continue;
                    }
                };
                tracing::info!(
                    "negotiate_input_type: 可用格式[{}] = {:?} GUID={:08x}",
                    i,
                    fmt,
                    subtype.data1
                );
                let _ = available.SetUINT64(
                    &MF_MT_FRAME_SIZE as *const _,
                    Self::pack_size(self.width, self.height),
                );
                let _ = available.SetUINT64(
                    &MF_MT_FRAME_RATE as *const _,
                    Self::pack_size(self.frame_rate, 1),
                );
                let _ = available.SetUINT32(
                    &MF_MT_INTERLACE_MODE as *const _,
                    MFVideoInterlace_Progressive.0 as u32,
                );
                let _ = available.SetUINT32(
                    &MF_MT_VIDEO_NOMINAL_RANGE as *const _,
                    MFNominalRange_0_255.0 as u32,
                );
                match self.transform.SetInputType(0, &available, 0) {
                    Ok(()) => {
                        tracing::info!("negotiate_input_type: 选中 {:?} (index={})", fmt, i);
                        return Some(fmt);
                    }
                    Err(e) => {
                        tracing::warn!(
                            "negotiate_input_type: {:?} SetInputType 失败: 0x{:x}",
                            fmt,
                            e.code().0
                        );
                        continue;
                    }
                }
            }

            tracing::info!("negotiate_input_type: 枚举失败,尝试直接构造已知格式");
            for fmt in [
                InputFormat::Iyuv,
                InputFormat::Nv12,
                InputFormat::Yv12,
                InputFormat::Yuyv,
                InputFormat::Rgb32,
            ] {
                if self.try_set_input_type(fmt).is_some() {
                    return Some(fmt);
                }
            }
            None
        }

        unsafe fn guid_to_format(guid: &windows::core::GUID) -> Option<InputFormat> {
            const STD_DATA2: u16 = 0x0010;
            const STD_DATA3: u16 = 0x8000;
            const STD_DATA4: [u8; 8] = [0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71, 0x00, 0x00];
            if guid.data2 != STD_DATA2 || guid.data3 != STD_DATA3 || guid.data4 != STD_DATA4 {
                return None;
            }
            let fourcc = guid.data1;
            match fourcc {
                0x3231564e => Some(InputFormat::Nv12),
                0x56555949 => Some(InputFormat::Iyuv),
                0x32315659 => Some(InputFormat::Yv12),
                0x32595559 => Some(InputFormat::Yuyv),
                0x32344252 => Some(InputFormat::Rgb32),
                _ => None,
            }
        }

        /// 构建并设置 output type(参考 MFWebCamRtp:从 input type CopyAllItems,再修改)
        ///
        /// 流程:
        /// 1. 构建基础 input type(IYUV,含 major/subtype/frame_size/frame_rate/interlace/nominal_range)
        /// 2. 创建 output type,从 input type CopyAllItems(复制所有属性)
        /// 3. 修改 output type:subtype = H264/HEVC,添加 avg_bitrate, interlace_mode, mpeg2_profile
        /// 4. SetOutputType
        unsafe fn set_output_type(&self) -> Option<()> {
            let output_subtype = match self.codec {
                Codec::H264 => MFVideoFormat_H264,
                Codec::Hevc => MFVideoFormat_HEVC,
            };
            // 构建基础 input type(IYUV,参考 MFWebCamRtp 使用 IYUV 作为基础)
            let base_input = self.build_base_type(Self::format_to_subtype(InputFormat::Iyuv))?;
            // 创建 output type,从 input type CopyAllItems
            let output_type = MFCreateMediaType().ok()?;
            base_input
                .CopyAllItems(&output_type)
                .map_err(|e| {
                    tracing::warn!(
                        "set_output_type: CopyAllItems 失败: 0x{:x}",
                        e.code().0
                    );
                })
                .ok()?;
            // 修改 output type
            output_type
                .SetGUID(&MF_MT_SUBTYPE as *const _, &output_subtype as *const _)
                .ok()?;
            output_type
                .SetUINT32(&MF_MT_AVG_BITRATE as *const _, self.avg_bitrate)
                .ok()?;
            output_type
                .SetUINT32(
                    &MF_MT_INTERLACE_MODE as *const _,
                    MFVideoInterlace_Progressive.0 as u32,
                )
                .ok()?;
            if self.codec == Codec::H264 {
                const H264_PROFILE_BASELINE: u32 = 66;
                let _ = output_type.SetUINT32(
                    &MF_MT_MPEG2_PROFILE as *const _,
                    H264_PROFILE_BASELINE,
                );
            }
            match self.transform.SetOutputType(0, &output_type, 0) {
                Ok(()) => {
                    tracing::info!(
                        "set_output_type: SetOutputType 成功 {}x{} {:?}",
                        self.width,
                        self.height,
                        self.codec
                    );
                    Some(())
                }
                Err(e) => {
                    tracing::warn!(
                        "set_output_type: SetOutputType 失败: 0x{:x} ({}x{})",
                        e.code().0,
                        self.width,
                        self.height
                    );
                    None
                }
            }
        }

        /// 通知流开始(BEGIN_STREAMING + START_OF_STREAM)
        /// 参考 MFWebCamRtp:在 SetOutputType → SetInputType → GetInputStatus 之后调用
        unsafe fn notify_stream_start(&self) -> Option<()> {
            let hr1 = self
                .transform
                .ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
            let hr2 = self
                .transform
                .ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
            match (hr1, hr2) {
                (Ok(()), Ok(())) => Some(()),
                (Err(e1), _) => {
                    tracing::warn!(
                        "notify_stream_start: BEGIN_STREAMING 失败: 0x{:x}",
                        e1.code().0
                    );
                    None
                }
                (_, Err(e2)) => {
                    tracing::warn!(
                        "notify_stream_start: START_OF_STREAM 失败: 0x{:x}",
                        e2.code().0
                    );
                    None
                }
            }
        }

        /// GetInputStatus 检查 + 通知流开始
        /// 返回 true 表示编码器就绪可以接受数据
        unsafe fn check_input_status_and_start(&self, fmt: InputFormat) -> bool {
            match self.transform.GetInputStatus(0) {
                Ok(status) if status == MFT_INPUT_STATUS_ACCEPT_DATA.0 as u32 => {
                    tracing::info!(
                        "check_input_status_and_start: GetInputStatus = ACCEPT_DATA format={:?}",
                        fmt
                    );
                }
                Ok(status) => {
                    tracing::warn!(
                        "check_input_status_and_start: GetInputStatus = 0x{:x} 不接受数据 format={:?}",
                        status,
                        fmt
                    );
                    return false;
                }
                Err(e) => {
                    tracing::warn!(
                        "check_input_status_and_start: GetInputStatus 失败: 0x{:x} format={:?}",
                        e.code().0,
                        fmt
                    );
                    return false;
                }
            }
            self.notify_stream_start().is_some()
        }

        /// 配置编码器输入/输出类型
        ///
        /// 参考 MFWebCamRtp 示例的正确顺序:
        /// 1. SetOutputType(先)
        /// 2. SetInputType / negotiate_input_type(后)
        /// 3. GetInputStatus 检查是否接受数据
        /// 4. ProcessMessage(BEGIN_STREAMING)
        /// 5. ProcessMessage(START_OF_STREAM)
        ///
        /// 关键修复:不再提前发送 BEGIN_STREAMING(之前在 SetOutputType/SetInputType 之前发送是错误的)
        unsafe fn configure(&mut self) -> Option<()> {
            tracing::info!(
                "configure: 开始 codec={:?} {}x{} is_async={}",
                self.codec,
                self.width,
                self.height,
                self.is_async
            );

            // Output-first 路径(标准顺序,参考 MFWebCamRtp):
            // SetOutputType → negotiate_input_type → GetInputStatus → BEGIN_STREAMING → START_OF_STREAM
            if self.set_output_type().is_some() {
                if let Some(fmt) = self.negotiate_input_type() {
                    self.input_format = fmt;
                    tracing::info!(
                        "configure: SetOutputType + negotiate_input_type 成功 format={:?} {}x{}",
                        fmt,
                        self.width,
                        self.height
                    );
                    if self.check_input_status_and_start(fmt) {
                        self.initialized = true;
                        return Some(());
                    }
                    let _ = self.transform.SetInputType(0, None, 0);
                    let _ = self.transform.SetOutputType(0, None, 0);
                } else {
                    let _ = self.transform.SetOutputType(0, None, 0);
                }
            }

            // Input-first 路径(fallback):
            // SetInputType → SetOutputType → GetInputStatus → BEGIN_STREAMING → START_OF_STREAM
            // 优先 IYUV(软件编码器首选,MFWebCamRtp 使用)
            for fmt in [
                InputFormat::Iyuv,
                InputFormat::Nv12,
                InputFormat::Yv12,
                InputFormat::Yuyv,
                InputFormat::Rgb32,
            ] {
                if self.try_set_input_type(fmt).is_some()
                    && self.set_output_type().is_some()
                {
                    self.input_format = fmt;
                    tracing::info!(
                        "configure: 成功(input-first) format={:?} {}x{}",
                        fmt,
                        self.width,
                        self.height
                    );
                    if self.check_input_status_and_start(fmt) {
                        self.initialized = true;
                        return Some(());
                    }
                }
                let _ = self.transform.SetInputType(0, None, 0);
                let _ = self.transform.SetOutputType(0, None, 0);
            }

            tracing::error!(
                "configure: 所有配置方式都失败 codec={:?} {}x{}",
                self.codec,
                self.width,
                self.height
            );
            None
        }

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
                        bgra.len(),
                        expected_bgra,
                        self.width,
                        self.height
                    );
                }
                return None;
            }

            let (frame_data, data_label) = match self.input_format {
                InputFormat::Nv12 => {
                    let nv12 = bgra_to_nv12(bgra, self.width as usize, self.height as usize);
                    (nv12, "nv12")
                }
                InputFormat::Iyuv => {
                    let iyuv = bgra_to_iyuv(bgra, self.width as usize, self.height as usize);
                    (iyuv, "iyuv")
                }
                InputFormat::Yv12 => {
                    let yv12 = bgra_to_yv12(bgra, self.width as usize, self.height as usize);
                    (yv12, "yv12")
                }
                InputFormat::Yuyv => {
                    let yuyv = bgra_to_yuyv(bgra, self.width as usize, self.height as usize);
                    (yuyv, "yuyv")
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
                        tracing::error!(
                            "encode_frame: buffer 无效 ptr={} max_len={} need={}",
                            ptr.is_null(),
                            max_len,
                            data_len
                        );
                    }
                    return None;
                }
                std::ptr::copy_nonoverlapping(frame_data.as_ptr(), ptr, data_len);
                let _ = buffer.SetCurrentLength(data_len as u32);
                let _ = buffer.Unlock();

                sample.AddBuffer(&buffer).ok()?;

                let timestamp =
                    self.frame_index * 10_000_000 / self.frame_rate as u64;
                let duration = 10_000_000 / self.frame_rate as u64;
                let _ = sample.SetSampleTime(timestamp as i64);
                let _ = sample.SetSampleDuration(duration as i64);

                if self.transform.ProcessInput(0, &sample, 0).is_err() {
                    if self.frame_index < 3 {
                        tracing::error!(
                            "encode_frame: ProcessInput 失败 frame_index={}",
                            self.frame_index
                        );
                    }
                    self.frame_index += 1;
                    return None;
                }

                let need_debug = self.frame_index < 3;
                if need_debug {
                    tracing::info!(
                        "encode_frame: ProcessInput 成功 frame_index={} {}_len={}",
                        self.frame_index,
                        data_label,
                        data_len
                    );
                }
                self.frame_index += 1;

                let result = self.collect_output();
                if need_debug {
                    tracing::info!(
                        "encode_frame: collect_output 返回 {} 字节 frame_index={}",
                        result.as_ref().map(|v| v.len()).unwrap_or(0),
                        self.frame_index
                    );
                }
                result
            }
        }

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
                        if self.frame_index < 3 {
                            tracing::info!(
                                "collect_output: ProcessOutput 返回 0x{:x} (loop_count={} frame_index={})",
                                e.code().0,
                                loop_count,
                                self.frame_index
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

        pub fn drain(&mut self) -> Vec<Vec<u8>> {
            if !self.initialized {
                return Vec::new();
            }
            unsafe {
                let _ = self
                    .transform
                    .ProcessMessage(MFT_MESSAGE_COMMAND_DRAIN, 0);
            }
            let mut outputs = Vec::new();
            loop {
                match unsafe { self.collect_output() } {
                    Some(nalu) => outputs.push(nalu),
                    None => break,
                }
            }
            unsafe {
                let _ = self
                    .transform
                    .ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
                let _ = self
                    .transform
                    .ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
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

    fn bgra_to_nv12(bgra: &[u8], width: usize, height: usize) -> Vec<u8> {
        debug_assert!(width % 2 == 0 && height % 2 == 0, "NV12 要求宽高为偶数");
        let y_size = width * height;
        let uv_size = width * (height / 2);
        let mut nv12 = vec![0u8; y_size + uv_size];

        let (y_plane, uv_plane) = nv12.split_at_mut(y_size);

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

        let half_h = height / 2;
        let half_w = width / 2;
        for row in 0..half_h {
            let uv_row = row * width;
            let py = row * 2;
            for col in 0..half_w {
                let px = col * 2;
                let i00 = (py * width + px) * 4;
                let i01 = (py * width + (px + 1)) * 4;
                let i10 = ((py + 1) * width + px) * 4;
                let i11 = ((py + 1) * width + (px + 1)) * 4;

                let b = (bgra[i00] as i32
                    + bgra[i01] as i32
                    + bgra[i10] as i32
                    + bgra[i11] as i32)
                    >> 2;
                let g = (bgra[i00 + 1] as i32
                    + bgra[i01 + 1] as i32
                    + bgra[i10 + 1] as i32
                    + bgra[i11 + 1] as i32)
                    >> 2;
                let r = (bgra[i00 + 2] as i32
                    + bgra[i01 + 2] as i32
                    + bgra[i10 + 2] as i32
                    + bgra[i11 + 2] as i32)
                    >> 2;

                let u = (((-43 * r - 84 * g + 127 * b + 128) >> 8) + 128).clamp(0, 255) as u8;
                let v = (((127 * r - 106 * g - 21 * b + 128) >> 8) + 128).clamp(0, 255) as u8;

                let uv_idx = uv_row + col * 2;
                uv_plane[uv_idx] = u;
                uv_plane[uv_idx + 1] = v;
            }
        }

        nv12
    }

    fn bgra_to_iyuv(bgra: &[u8], width: usize, height: usize) -> Vec<u8> {
        let y_size = width * height;
        let uv_size = (width / 2) * (height / 2);
        let mut iyuv = vec![0u8; y_size + uv_size * 2];
        let (y_plane, rest) = iyuv.split_at_mut(y_size);
        let (u_plane, v_plane) = rest.split_at_mut(uv_size);

        for row in 0..height {
            for col in 0..width {
                let i = (row * width + col) * 4;
                let b = bgra[i] as i32;
                let g = bgra[i + 1] as i32;
                let r = bgra[i + 2] as i32;
                let y = (77 * r + 150 * g + 29 * b + 128) >> 8;
                y_plane[row * width + col] = y.clamp(0, 255) as u8;
            }
        }

        let half_h = height / 2;
        let half_w = width / 2;
        for row in 0..half_h {
            for col in 0..half_w {
                let py = row * 2;
                let px = col * 2;
                let i00 = (py * width + px) * 4;
                let i01 = (py * width + (px + 1)) * 4;
                let i10 = ((py + 1) * width + px) * 4;
                let i11 = ((py + 1) * width + (px + 1)) * 4;
                let b = (bgra[i00] as i32 + bgra[i01] as i32 + bgra[i10] as i32 + bgra[i11] as i32) >> 2;
                let g = (bgra[i00 + 1] as i32
                    + bgra[i01 + 1] as i32
                    + bgra[i10 + 1] as i32
                    + bgra[i11 + 1] as i32)
                    >> 2;
                let r = (bgra[i00 + 2] as i32
                    + bgra[i01 + 2] as i32
                    + bgra[i10 + 2] as i32
                    + bgra[i11 + 2] as i32)
                    >> 2;
                let u = (((-43 * r - 84 * g + 127 * b + 128) >> 8) + 128).clamp(0, 255) as u8;
                let v = (((127 * r - 106 * g - 21 * b + 128) >> 8) + 128).clamp(0, 255) as u8;
                u_plane[row * half_w + col] = u;
                v_plane[row * half_w + col] = v;
            }
        }
        iyuv
    }

    fn bgra_to_yv12(bgra: &[u8], width: usize, height: usize) -> Vec<u8> {
        let y_size = width * height;
        let uv_size = (width / 2) * (height / 2);
        let mut yv12 = vec![0u8; y_size + uv_size * 2];
        let (y_plane, rest) = yv12.split_at_mut(y_size);
        let (v_plane, u_plane) = rest.split_at_mut(uv_size);

        for row in 0..height {
            for col in 0..width {
                let i = (row * width + col) * 4;
                let b = bgra[i] as i32;
                let g = bgra[i + 1] as i32;
                let r = bgra[i + 2] as i32;
                let y = (77 * r + 150 * g + 29 * b + 128) >> 8;
                y_plane[row * width + col] = y.clamp(0, 255) as u8;
            }
        }

        let half_h = height / 2;
        let half_w = width / 2;
        for row in 0..half_h {
            for col in 0..half_w {
                let py = row * 2;
                let px = col * 2;
                let i00 = (py * width + px) * 4;
                let i01 = (py * width + (px + 1)) * 4;
                let i10 = ((py + 1) * width + px) * 4;
                let i11 = ((py + 1) * width + (px + 1)) * 4;
                let b = (bgra[i00] as i32 + bgra[i01] as i32 + bgra[i10] as i32 + bgra[i11] as i32) >> 2;
                let g = (bgra[i00 + 1] as i32
                    + bgra[i01 + 1] as i32
                    + bgra[i10 + 1] as i32
                    + bgra[i11 + 1] as i32)
                    >> 2;
                let r = (bgra[i00 + 2] as i32
                    + bgra[i01 + 2] as i32
                    + bgra[i10 + 2] as i32
                    + bgra[i11 + 2] as i32)
                    >> 2;
                let u = (((-43 * r - 84 * g + 127 * b + 128) >> 8) + 128).clamp(0, 255) as u8;
                let v = (((127 * r - 106 * g - 21 * b + 128) >> 8) + 128).clamp(0, 255) as u8;
                v_plane[row * half_w + col] = v;
                u_plane[row * half_w + col] = u;
            }
        }
        yv12
    }

    fn bgra_to_yuyv(bgra: &[u8], width: usize, height: usize) -> Vec<u8> {
        let mut yuyv = vec![0u8; width * height * 2];
        for row in 0..height {
            for col in (0..width).step_by(2) {
                let i0 = (row * width + col) * 4;
                let i1 = (row * width + (col + 1)) * 4;
                let b0 = bgra[i0] as i32;
                let g0 = bgra[i0 + 1] as i32;
                let r0 = bgra[i0 + 2] as i32;
                let b1 = bgra[i1] as i32;
                let g1 = bgra[i1 + 1] as i32;
                let r1 = bgra[i1 + 2] as i32;
                let y0 = (77 * r0 + 150 * g0 + 29 * b0 + 128) >> 8;
                let y1 = (77 * r1 + 150 * g1 + 29 * b1 + 128) >> 8;
                let b = (b0 + b1) >> 1;
                let g = (g0 + g1) >> 1;
                let r = (r0 + r1) >> 1;
                let u = (((-43 * r - 84 * g + 127 * b + 128) >> 8) + 128).clamp(0, 255) as u8;
                let v = (((127 * r - 106 * g - 21 * b + 128) >> 8) + 128).clamp(0, 255) as u8;
                let o = (row * width + col) * 2;
                yuyv[o] = y0.clamp(0, 255) as u8;
                yuyv[o + 1] = u;
                yuyv[o + 2] = y1.clamp(0, 255) as u8;
                yuyv[o + 3] = v;
            }
        }
        yuyv
    }

    struct EncoderEntry {
        encoder: Mutex<MFEncoder>,
        codec: Codec,
        width: u32,
        height: u32,
    }

    static ENCODER: OnceLock<Mutex<Option<EncoderEntry>>> = OnceLock::new();

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

        let need_rebuild = match guard.as_ref() {
            Some(entry) => entry.codec != codec || entry.width != width || entry.height != height,
            None => true,
        };

        if need_rebuild {
            let new_encoder = MFEncoder::new(codec, width, height, frame_rate, avg_bitrate);
            if new_encoder.is_none() {
                tracing::error!(
                    "MFEncoder::new 失败: codec={:?} {}x{} bitrate={}",
                    codec,
                    width,
                    height,
                    avg_bitrate
                );
            } else {
                tracing::info!(
                    "MFEncoder 创建成功: codec={:?} {}x{} bitrate={}",
                    codec,
                    width,
                    height,
                    avg_bitrate
                );
            }
            *guard = new_encoder.map(|enc| EncoderEntry {
                encoder: Mutex::new(enc),
                codec,
                width,
                height,
            });
        }

        let entry = guard.as_ref()?;
        let mut enc_guard = entry.encoder.lock().ok()?;
        enc_guard.encode_frame(bgra)
    }

    pub fn probe_codec_support(codec: Codec) -> bool {
        unsafe {
            static MF_STARTED: OnceLock<()> = OnceLock::new();
            MF_STARTED.get_or_init(|| {
                let _ = MFStartup(MF_API_VERSION, MFSTARTUP_LITE);
            });

            !MFEncoder::enum_encoders_all(codec).is_empty()
        }
    }

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
