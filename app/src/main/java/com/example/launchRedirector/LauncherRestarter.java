package com.example.launchRedirector;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class LauncherRestarter {

    public enum Status {
        SENT,
        NEED_ROOT,
        NO_PERMISSION,
        TIMEOUT,
        INTERRUPTED
    }

    public static final class Result {
        public final Status status;
        public final String message;

        private Result(Status status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    private LauncherRestarter() {}

    public static Result forceStop(List<String> packages, int timeoutSec) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            try (DataOutputStream os = new DataOutputStream(process.getOutputStream())) {
                for (String pkg : packages) {
                    os.writeBytes("am force-stop " + pkg + "\n");
                }
                os.writeBytes("exit\n");
                os.flush();
            }

            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(Status.TIMEOUT, "su timeout");
            }
            return new Result(Status.SENT, null);
        } catch (IOException e) {
            return new Result(Status.NEED_ROOT, e.getMessage());
        } catch (SecurityException e) {
            return new Result(Status.NO_PERMISSION, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Status.INTERRUPTED, e.getMessage());
        }
    }
}
