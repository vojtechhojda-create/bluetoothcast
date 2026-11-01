Add-Type -Namespace pl -Name k -MemberDefinition '[DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, int flags, int extra);'
while (($line = [Console]::In.ReadLine()) -ne $null) {
  try {
    $p = $line.Split(' ', 2)
    switch ($p[0]) {
      'key' {
        $vk = [byte][int]$p[1]
        [pl.k]::keybd_event($vk, 0, 1, 0)
        [pl.k]::keybd_event($vk, 0, 3, 0)
      }
      'setclip' {
        $t = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p[1]))
        Set-Clipboard -Value $t
      }
    }
  } catch {}
}
