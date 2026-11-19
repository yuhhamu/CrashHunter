#define AppName "CrashHunter"
#ifndef AppVersion
#define AppVersion "0.0.0-dev"
#endif
[Setup]
AppId={{C4A8E2F1-6B3D-4E7A-9F21-3D5B8C0E7A42}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=yuhhamu
AppPublisherURL=https://github.com/
DefaultDirName={localappdata}\Programs\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\build\release
OutputBaseFilename=CrashHunter-Setup-{#AppVersion}
SetupIconFile=crashhunter.ico
UninstallDisplayIcon={app}\CrashHunter.exe
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
LicenseFile=..\LICENSE
WizardStyle=modern

[Languages]
Name: "ja"; MessagesFile: "compiler:Languages\Japanese.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Messages]
ja.WelcomeLabel2=[name/ver] をインストールします。%n%nMinecraft の公式製品ではありません。Mojang、Microsoft、対応しているランチャーの開発元が承認・関与したものではありません。
en.WelcomeLabel2=This will install [name/ver] on your computer.%n%nNOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG, MICROSOFT OR THE DEVELOPERS OF SUPPORTED LAUNCHERS.

[InstallDelete]
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "..\build\jpackage\CrashHunter\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion
Source: "..\LICENSE"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\NOTICE.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\README.md"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\CrashHunter"; Filename: "{app}\CrashHunter.exe"
Name: "{userdesktop}\CrashHunter"; Filename: "{app}\CrashHunter.exe"; Tasks: desktopicon

[Tasks]
Name: desktopicon; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Run]
Filename: "{app}\CrashHunter.exe"; Description: "{cm:LaunchProgram,CrashHunter}"; Flags: nowait postinstall skipifsilent
Filename: "{app}\CrashHunter.exe"; Flags: nowait; Check: Relaunch

[Code]
function Relaunch: Boolean;
begin
  Result := WizardSilent and (ExpandConstant('{param:RELAUNCH|0}') = '1');
end;

function CrashHunterRunning: Boolean;
var
  Locator, Service, Items: Variant;
begin
  Result := False;
  try
    Locator := CreateOleObject('WbemScripting.SWbemLocator');
    Service := Locator.ConnectServer('.', 'root\CIMV2');
    Items := Service.ExecQuery('SELECT ProcessId FROM Win32_Process WHERE Name = ''CrashHunter.exe''');
    Result := Items.Count > 0;
  except
    Result := False;
  end;
end;

function InitializeSetup: Boolean;
var
  I: Integer;
begin
  if WizardSilent then
  begin
    I := 0;
    while CrashHunterRunning and (I < 60) do
    begin
      Sleep(500);
      I := I + 1;
    end;
    Log(Format('Waited for CrashHunter to exit: %d ms', [I * 500]));
  end;
  Result := True;
end;
