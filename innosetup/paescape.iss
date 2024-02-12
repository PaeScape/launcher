; requires netcorecheck.exe and netcorecheck_x64.exe (see CodeDependencies.iss)
#define public Dependency_Path_NetCoreCheck "dependencies\"

#include "CodeDependencies.iss"

[Setup]
AppName=PaeScape Launcher
AppPublisher=PaeScape
UninstallDisplayName=PaeScape
AppVersion=@project.version@
AppSupportURL=https://paescape.net/
DefaultDirName={localappdata}\PaeScape
; vcredist queues files to be replaced at next reboot, however it doesn't seem to matter
RestartIfNeededByRun=no

; ~30 mb for the repo the launcher downloads
ExtraDiskSpaceRequired=30000000
ArchitecturesAllowed=x64
PrivilegesRequired=lowest

WizardSmallImageFile=@basedir@/innosetup/paescape_small.bmp
SetupIconFile=@basedir@/paescape.ico
UninstallDisplayIcon={app}\PaeScape.exe

Compression=lzma2
SolidCompression=yes

OutputDir=@basedir@
OutputBaseFilename=PaeScapeSetup

[Tasks]
Name: DesktopIcon; Description: "Create a &desktop icon";

[Files]
Source: "@basedir@\native-win64\PaeScape.exe"; DestDir: "{app}"
Source: "@basedir@\native-win64\PaeScapeLauncher.jar"; DestDir: "{app}"
Source: "@basedir@\native-win64\config.json"; DestDir: "{app}"
Source: "@basedir@\native-win64\jre\*"; DestDir: "{app}\jre"; Flags: recursesubdirs

[Icons]
; start menu
Name: "{userprograms}\PaeScape"; Filename: "{app}\PaeScape.exe"
Name: "{userdesktop}\PaeScape"; Filename: "{app}\PaeScape.exe"; Tasks: DesktopIcon

[Run]
Filename: "{app}\PaeScape.exe"; Description: "&Open PaeScape"; Flags: postinstall skipifsilent nowait

[InstallDelete]
; Delete the old jvm so it doesn't try to load old stuff with the new vm and crash
Type: filesandordirs; Name: "{app}"

[UninstallDelete]
Type: filesandordirs; Name: "{%USERPROFILE}\.paescape\repository2"

[Code]
function InitializeSetup: Boolean;
begin
  Dependency_AddVC2015To2022;
  Result := True;
end;