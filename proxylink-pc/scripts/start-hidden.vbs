Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
root = fso.GetParentFolderName(fso.GetParentFolderName(WScript.ScriptFullName))
dir = sh.ExpandEnvironmentStrings("%APPDATA%") & "\proxylink"
If Not fso.FolderExists(dir) Then fso.CreateFolder(dir)
sh.Run "cmd /c cd /d """ & root & """ && node dist\index.js > """ & dir & "\log.txt"" 2>&1", 0, False
