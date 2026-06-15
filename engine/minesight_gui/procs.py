from __future__ import annotations

from PySide6.QtCore import QObject, QProcess, QProcessEnvironment, Signal


class ManagedProcess(QObject):
    """A QProcess wrapper that streams merged stdout/stderr line-by-line."""

    line = Signal(str)
    started = Signal()
    finished = Signal(int)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.proc: QProcess | None = None

    @property
    def running(self) -> bool:
        return self.proc is not None and self.proc.state() != QProcess.ProcessState.NotRunning

    def start(self, program: str, args: list[str], cwd: str) -> None:
        if self.running:
            return
        p = QProcess(self)
        p.setProgram(program)
        p.setArguments(args)
        p.setWorkingDirectory(cwd)
        p.setProcessChannelMode(QProcess.ProcessChannelMode.MergedChannels)
        env = QProcessEnvironment.systemEnvironment()
        # Child Python processes: live output, and no cp1252 crashes on emoji.
        env.insert("PYTHONUNBUFFERED", "1")
        env.insert("PYTHONIOENCODING", "utf-8")
        p.setProcessEnvironment(env)
        p.readyReadStandardOutput.connect(self._read)
        p.started.connect(self.started)
        p.finished.connect(lambda code, _status: self.finished.emit(code))
        self.proc = p
        p.start()

    def _read(self) -> None:
        data = bytes(self.proc.readAllStandardOutput()).decode("utf-8", "replace")
        # tqdm progress bars use bare \r; treat them as line endings.
        for ln in data.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
            if ln.strip():
                self.line.emit(ln)

    def send(self, text: str) -> None:
        """Write a line to the process's stdin - e.g. a server console command
        forwarded through gradlew runServer to the Paper console."""
        if self.running:
            self.proc.write((text + "\n").encode("utf-8"))

    def stop(self) -> None:
        # kill() rather than terminate(): console children ignore WM_CLOSE on Windows.
        if self.running:
            self.proc.kill()
