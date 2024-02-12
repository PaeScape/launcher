package online.paescape.launcher.utils;

import online.paescape.launcher.PaeScapeSplashScreen;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import java.io.IOException;

public class GitHubUtils {
    private static final GitHub GITHUB;

    static {
        try {
            GITHUB = GitHub.connectAnonymously();
        } catch (IOException e) {
            PaeScapeSplashScreen.init(null);
            PaeScapeSplashScreen.setError("Error connecting to GitHub!", "We couldn't connect to the GitHub repository.");
            throw new RuntimeException(e);
        }
    }

    public static GHRepository getRepo() {
        try {
            return GITHUB.getRepository("PaeScape/static.paescape.online");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
