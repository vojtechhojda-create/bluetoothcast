if (args.Length > 0 && args[0] == "--list")
{
    Lister.Run();
    return;
}
if (args.Length > 2 && args[0] == "--play" && int.TryParse(args[1], out var prate) && int.TryParse(args[2], out var pch))
{
    Player.Run(prate, pch);
    return;
}
if (args.Length > 1 && args[0] == "--pid" && int.TryParse(args[1], out var pid) && pid > 0)
{
    ProcessCapture.Run(pid);
    return;
}
SystemCapture.Run();
