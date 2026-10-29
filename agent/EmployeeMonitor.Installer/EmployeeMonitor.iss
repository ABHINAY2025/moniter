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
Source: "publish\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion; AfterInstall: WriteAgentConfig

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

procedure JsonEscape(var s: string);
begin
  StringChangeEx(s, '\', '\\', True);
  StringChangeEx(s, '"', '\"', True);
end;

// Write the backend URL + enrollment token where the agent reads them directly (appsettings.Production.json,
// loaded on top of appsettings.json). This makes the agent config-driven, so the immediate post-install launch
// and the logon task both connect without depending on environment-variable propagation timing.
procedure WriteAgentConfig;
var
  url, tok, json: string;
begin
  url := GetUrl(''); JsonEscape(url);
  tok := GetToken(''); JsonEscape(tok);
  json :=
    '{' + #13#10 +
    '  "Agent": {' + #13#10 +
    '    "BackendBaseUrl": "' + url + '",' + #13#10 +
    '    "EnrollmentToken": "' + tok + '"' + #13#10 +
    '  }' + #13#10 +
    '}' + #13#10;
  SaveStringToFile(ExpandConstant('{app}\appsettings.Production.json'), json, False);
end;

// Stop a running agent/tray and clear queued tasks before files are copied, so a reinstall never hits a locked
// file (which previously left a half-updated, non-starting install).
procedure StopRunningAgent;
var
  rc: Integer;
begin
  Exec(ExpandConstant('{sys}\schtasks.exe'), '/End /TN "EmployeeMonitor Agent"', '', SW_HIDE, ewWaitUntilTerminated, rc);
  Exec(ExpandConstant('{sys}\schtasks.exe'), '/End /TN "EmployeeMonitor Tray"', '', SW_HIDE, ewWaitUntilTerminated, rc);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM EmployeeMonitorAgent.exe', '', SW_HIDE, ewWaitUntilTerminated, rc);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM EmployeeMonitorTray.exe', '', SW_HIDE, ewWaitUntilTerminated, rc);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then
    StopRunningAgent;
end;

[Run]
; Store machine-wide config too (redundant with appsettings.Production.json, kept for compatibility).
Filename: "{sys}\cmd.exe"; Parameters: "/c setx /M EM_ENROLLMENT_TOKEN ""{code:GetToken}"""; Flags: runhidden
Filename: "{sys}\cmd.exe"; Parameters: "/c setx /M Agent__BackendBaseUrl ""{code:GetUrl}"""; Flags: runhidden
; Register the agent and the visible tray to start in each user's session at logon.
Filename: "{sys}\schtasks.exe"; Parameters: "/Create /TN ""EmployeeMonitor Agent"" /TR ""\""{app}\EmployeeMonitorAgent.exe\"""" /SC ONLOGON /RL LIMITED /F"; Flags: runhidden
Filename: "{sys}\schtasks.exe"; Parameters: "/Create /TN ""EmployeeMonitor Tray"" /TR ""\""{app}\EmployeeMonitorTray.exe\"""" /SC ONLOGON /RL LIMITED /F"; Flags: runhidden
; Start the agent + tray NOW in the logged-on user's session (not elevated) so the device comes online
; immediately after install, without waiting for the next logon. Runs in silent installs too.
Filename: "{app}\EmployeeMonitorAgent.exe"; WorkingDir: "{app}"; Flags: nowait runasoriginaluser
Filename: "{app}\EmployeeMonitorTray.exe"; WorkingDir: "{app}"; Flags: nowait runasoriginaluser

[UninstallRun]
Filename: "{sys}\schtasks.exe"; Parameters: "/End /TN ""EmployeeMonitor Agent"""; Flags: runhidden; RunOnceId: "endagent"
Filename: "{sys}\schtasks.exe"; Parameters: "/End /TN ""EmployeeMonitor Tray"""; Flags: runhidden; RunOnceId: "endtray"
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM EmployeeMonitorAgent.exe"; Flags: runhidden; RunOnceId: "killagent"
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM EmployeeMonitorTray.exe"; Flags: runhidden; RunOnceId: "killtray"
Filename: "{sys}\schtasks.exe"; Parameters: "/Delete /TN ""EmployeeMonitor Agent"" /F"; Flags: runhidden; RunOnceId: "delagent"
Filename: "{sys}\schtasks.exe"; Parameters: "/Delete /TN ""EmployeeMonitor Tray"" /F"; Flags: runhidden; RunOnceId: "deltray"

[UninstallDelete]
; Written post-install (not tracked by [Files]), so remove it explicitly.
Type: files; Name: "{app}\appsettings.Production.json"
