package io.github.efecaginyildirim.collector.ui;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Opens a URL in the user's default browser.
 *
 * <p>Uses the platform's own opener instead of {@code java.awt.Desktop}, so the packaged app does
 * not need the {@code java.desktop} module (about 12 MB of runtime).
 */
public final class Browser {

    private Browser() { }

    /**
     * @param uri http(s) URL to open
     * @return whether an opener could be started
     */
    public static boolean open(URI uri) {
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            throw new IllegalArgumentException("only http(s) URLs can be opened: " + uri);
        }
        try {
            new ProcessBuilder(command(System.getProperty("os.name", ""), uri.toString()))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The opener command for an operating system; separated for testing.
     *
     * @param osName value of {@code os.name}
     * @param url    URL to open (passed as one argument, never through a shell)
     * @return command and arguments
     */
    static List<String> command(String osName, String url) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return List.of("rundll32", "url.dll,FileProtocolHandler", url);
        }
        if (os.startsWith("mac") || os.startsWith("darwin")) {
            return List.of("open", url);
        }
        return List.of("xdg-open", url);
    }
}
