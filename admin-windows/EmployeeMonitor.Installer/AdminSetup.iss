; Inno Setup script for AdminSetup.exe — the admin console (WinUI 3) for IT administrators.
; Build:  iscc /DAdminVersion=1.0.0 AdminSetup.iss   (after the admin app is self-contained-published)
; The self-contained publish bundles the .NET + Windows App SDK runtime, so no prerequisites are needed.
; Unlike the agent, this is a normal foreground desktop app: shortcuts only, no scheduled tasks / background run.

#ifndef AdminVersion
  #define AdminVersion "1.0.0"
#endif

[Setup]
AppId={{C3F0A8B2-6D4E-4F9C-8B32-EMADMINCONSOLE}}
AppName=Employee Monitor Admin Console
AppVersion={#AdminVersion}
AppPublisher=Employee Monitoring Platform
DefaultDirName={autopf}\EmployeeMonitor\Admin
DefaultGroupName=Employee Monitor
DisableProgramGroupPage=yes
OutputBaseFilename=AdminSetup
OutputDir=Output
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayName=Employee Monitor Admin Console

[Files]
; The self-contained published admin app (produced by the publish command in the repo README).
Source: "..\EmployeeMonitor.Admin\publish-selfcontained\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{group}\Employee Monitor Admin"; Filename: "{app}\EmployeeMonitorAdmin.exe"
Name: "{autodesktop}\Employee Monitor Admin"; Filename: "{app}\EmployeeMonitorAdmin.exe"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"

[Run]
Filename: "{app}\EmployeeMonitorAdmin.exe"; Description: "Launch the Admin Console"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: files; Name: "{app}\appsettings.json"

[Code]
var
  UrlPage: TInputQueryWizardPage;

procedure InitializeWizard;
begin
  UrlPage := CreateInputQueryPage(wpSelectDir,
    'Backend', 'Point the console at your monitoring server',
    'Enter the backend API URL. Use https://... once your domain and TLS are set up.');
  UrlPage.Add('Backend API URL:', False);
  UrlPage.Values[0] := 'https://api.quantumdataleap.ai';
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if (CurPageID = UrlPage.ID) and (Trim(UrlPage.Values[0]) = '') then
  begin
    MsgBox('A backend URL is required.', mbError, MB_OK);
    Result := False;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  cfg: string;
begin
  if CurStep = ssPostInstall then
  begin
    cfg := '{' + #13#10 +
           '  "Backend": {' + #13#10 +
           '    "BaseUrl": "' + Trim(UrlPage.Values[0]) + '"' + #13#10 +
           '  }' + #13#10 +
           '}' + #13#10;
    SaveStringToFile(ExpandConstant('{app}\appsettings.json'), cfg, False);
  end;
end;
