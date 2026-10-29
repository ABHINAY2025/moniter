; Inno Setup script for EmployeeMonitorSetup.exe (Milestone 7).
; Build with:  iscc /DAgentVersion=1.0.0 EmployeeMonitor.iss   (after build-installer.ps1 publishes the binaries)
; Requires the published binaries in .\publish (produced by scripts/build-installer.ps1).
;
; The agent needs the interactive user session for idle/lock detection and screen capture, so it is registered as
; logon scheduled tasks (agent + visible tray) rather than a Session-0 service.

#ifndef AgentVersion
  #define AgentVersion "1.0.0"
#endif

[Setup]
AppId={{B2E9F7A1-5C3D-4E8B-9A21-EMPLOYEEMONITOR}}
AppName=Employee Monitor Agent
AppVersion={#AgentVersion}
AppPublisher=Employee Monitoring Platform
DefaultDirName={autopf}\EmployeeMonitor\Agent
DefaultGroupName=Employee Monitor
DisableProgramGroupPage=yes
OutputBaseFilename=EmployeeMonitorSetup
OutputDir=Output
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
InfoBeforeFile=monitoring-notice.txt
UninstallDisplayName=Employee Monitor Agent

[Files]
; build-installer.ps1 self-contained-publishes the agent + tray into .\publish
Source: "publish\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Code]
var
  ConfigPage: TInputQueryWizardPage;

procedure InitializeWizard;
begin
  ConfigPage := CreateInputQueryPage(wpSelectDir,
    'Enrollment', 'Connect this device to your monitoring server',
    'Enter the enrollment token issued by your IT administrator and the server URL.');
  ConfigPage.Add('Enrollment token:', False);
  ConfigPage.Add('Backend URL (https://...):', False);
  // Prefill from command line so unattended installs work:
  //   EmployeeMonitorSetup.exe /VERYSILENT /SUPPRESSMSGBOXES /TOKEN=enr_xxx [/URL=https://host]
  ConfigPage.Values[0] := ExpandConstant('{param:TOKEN|}');
  ConfigPage.Values[1] := ExpandConstant('{param:URL|https://api.quantumdataleap.ai}');
end;

function GetToken(Param: string): string;
begin
  Result := Trim(ConfigPage.Values[0]);
end;

function GetUrl(Param: string): string;
begin
  Result := Trim(ConfigPage.Values[1]);
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  // Only enforce the token in the interactive wizard; unattended installs supply it via /TOKEN=.
  if (CurPageID = ConfigPage.ID) and (Trim(ConfigPage.Values[0]) = '') and (not WizardSilent) then
  begin
    MsgBox('An enrollment token is required.', mbError, MB_OK);
    Result := False;
  end;
end;

[Run]
; Store machine-wide configuration the logon tasks inherit.
Filename: "{sys}\cmd.exe"; Parameters: "/c setx /M EM_ENROLLMENT_TOKEN ""{code:GetToken}"""; Flags: runhidden
Filename: "{sys}\cmd.exe"; Parameters: "/c setx /M Agent__BackendBaseUrl ""{code:GetUrl}"""; Flags: runhidden
; Register the agent and the visible tray to start in each user's session at logon.
Filename: "{sys}\schtasks.exe"; Parameters: "/Create /TN ""EmployeeMonitor Agent"" /TR ""\""{app}\EmployeeMonitorAgent.exe\"""" /SC ONLOGON /RL LIMITED /F"; Flags: runhidden
Filename: "{sys}\schtasks.exe"; Parameters: "/Create /TN ""EmployeeMonitor Tray"" /TR ""\""{app}\EmployeeMonitorTray.exe\"""" /SC ONLOGON /RL LIMITED /F"; Flags: runhidden
; Launch now for the installing user.
Filename: "{app}\EmployeeMonitorTray.exe"; Description: "Start monitoring indicator"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\schtasks.exe"; Parameters: "/Delete /TN ""EmployeeMonitor Agent"" /F"; Flags: runhidden
Filename: "{sys}\schtasks.exe"; Parameters: "/Delete /TN ""EmployeeMonitor Tray"" /F"; Flags: runhidden
