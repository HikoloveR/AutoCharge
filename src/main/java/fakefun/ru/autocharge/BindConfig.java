package fakefun.ru.autocharge;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class BindConfig {
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("autocharge.properties");

    private BindConfig() {
    }

    static BindState load() {
        if (!Files.exists(CONFIG_PATH)) {
            return BindState.unbound();
        }

        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(CONFIG_PATH)) {
            properties.load(reader);
        } catch (IOException ignored) {
            return BindState.unbound();
        }

        String type = properties.getProperty("type");
        String code = properties.getProperty("code");
        if (type == null || code == null) {
            return BindState.unbound();
        }

        try {
            return new BindState(BindType.valueOf(type), Integer.parseInt(code));
        } catch (IllegalArgumentException ignored) {
            return BindState.unbound();
        }
    }

    static void save(BindState state) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Properties properties = new Properties();
            if (state.isBound()) {
                properties.setProperty("type", state.type().name());
                properties.setProperty("code", Integer.toString(state.code()));
            }
            try (var writer = Files.newBufferedWriter(CONFIG_PATH)) {
                properties.store(writer, "AutoCharge bind config");
            }
        } catch (IOException ignored) {
        }
    }
}
