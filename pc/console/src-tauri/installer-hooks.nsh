; MeowMic NSIS 安装钩子:自动配置 Windows 防火墙规则
;
; 作用:解决"手机端连不上 PC 端"问题
; 原因:meowmic-server.exe 首次运行时 Windows 会弹防火墙授权弹窗,
;       用户关闭/拒绝后,server 的 control/touch/audio 端口被静默拦截,
;       局域网手机无法连接(本机 127.0.0.1 仍通,所以前端无错误提示)。
;
; 本钩子在安装时(管理员权限)自动添加入站规则,卸载时移除。
; Tauri 1.x resources 在 NSIS 安装后路径可能是 $INSTDIR\、$INSTDIR\resources\、
; $INSTDIR\bin\,三处都尝试添加,只有路径正确的规则会实际生效
; (netsh 对不存在的 program 路径会报错不创建)。

!macro NSIS_HOOK_POSTINSTALL
  ; 清理旧规则(避免升级残留)
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="MeowMic Server"'
  ; 尝试添加多个可能路径的规则
  nsExec::ExecToLog 'netsh advfirewall firewall add rule name="MeowMic Server" dir=in action=allow program="$INSTDIR\meowmic-server.exe" enable=yes profile=any'
  nsExec::ExecToLog 'netsh advfirewall firewall add rule name="MeowMic Server" dir=in action=allow program="$INSTDIR\resources\meowmic-server.exe" enable=yes profile=any'
  nsExec::ExecToLog 'netsh advfirewall firewall add rule name="MeowMic Server" dir=in action=allow program="$INSTDIR\bin\meowmic-server.exe" enable=yes profile=any'
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  ; 卸载时移除防火墙规则(按 name 删除所有同名规则)
  nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="MeowMic Server"'
!macroend
