package _1ms.playtimelink;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Strict, read-only loading of existing user configuration. */
final class SafeConfiguration {
    private SafeConfiguration() {}

    static YamlConfiguration load(Path file, InputStream defaults) throws IOException, InvalidConfigurationException {
        YamlConfiguration bundled = new YamlConfiguration();
        try(Reader reader = new InputStreamReader(defaults, StandardCharsets.UTF_8)) {
            bundled.load(reader);
        }
        YamlConfiguration user = new YamlConfiguration();
        // load(), unlike loadConfiguration(), propagates parse/read failures.
        // Existing files are never rewritten by startup or reload.
        try(Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            user.load(reader);
        }
        user.setDefaults(bundled);
        return user;
    }
}
