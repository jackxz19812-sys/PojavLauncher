package net.kdt.pojavlaunch.starlight;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.ModloaderDownloadListener;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModLoader;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthApi;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Starlight: mantiene instalado el modpack del servidor.
 * Lee la versión publicada en el panel web (la misma que usa el launcher de PC),
 * instala/actualiza el .mrpack en su propia instancia, crea el perfil "Starlight"
 * y adapta RAM, resolución y opciones gráficas al dispositivo.
 */
public final class StarlightModpack {
    private static final String TAG = "Starlight";
    public static final String MANIFEST_URL = "https://starlight.sarl/api/launcher/manifest";
    // Pojav renombra a un UUID aleatorio cualquier clave que no sea UUID, así que usamos uno fijo.
    public static final String PROFILE_KEY = "5a7f1e9c-0b1d-4c3a-9e2f-57a71167c0b1";
    private static final String PROFILE_GAMEDIR = "./custom_instances/starlight";
    private static final String INSTANCE = "custom_instances/starlight";
    private static final String SERVER_NAME = "Starlight";
    private static final String SERVER_IP = "starlight.sarl";

    private static final String PREF_SOURCE = "sl_modpack_source";
    private static final String PREF_VERSION = "sl_modpack_version";
    private static final String PREF_TUNED = "sl_tuned_for";

    private static volatile boolean sRunning = false;

    public interface Listener {
        /** Texto de estado para mostrar en el menú (hilo principal). */
        void onStatus(String text);
        /** Fin de la sincronización (hilo principal). */
        void onDone(boolean ok, String message);
    }

    private StarlightModpack() {}

    public static boolean isRunning() { return sRunning; }

    /** Comprueba el panel y, si hace falta, instala o actualiza el modpack. Corre en segundo plano. */
    public static void sync(Context context, Listener listener) {
        if (sRunning) return;
        sRunning = true;
        final Context ctx = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            boolean ok = false;
            String message;
            try {
                message = doSync(ctx, text -> main.post(() -> { if (listener != null) listener.onStatus(text); }));
                ok = true;
            } catch (Throwable t) {
                Log.e(TAG, "Error sincronizando el modpack", t);
                message = t.getMessage() != null ? t.getMessage() : t.toString();
            } finally {
                sRunning = false;
            }
            final boolean fOk = ok;
            final String fMessage = message;
            main.post(() -> { if (listener != null) listener.onDone(fOk, fMessage); });
        }, "StarlightModpack").start();
    }

    private interface Status { void post(String text); }

    private static String doSync(Context ctx, Status status) throws Exception {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        File instance = new File(Tools.DIR_GAME_HOME, INSTANCE);
        boolean installed = prefs.getString(PREF_SOURCE, null) != null
                && new File(instance, "mods").isDirectory() && profileExists();

        status.post("Comprobando modpack…");
        String url, version;
        try {
            JSONObject modpack = new JSONObject(fetch(MANIFEST_URL)).getJSONObject("modpack");
            url = modpack.getString("url");
            version = modpack.optString("version", "1");
        } catch (Exception e) {
            // sin internet: si ya está instalado se puede jugar igual
            if (!installed) throw new IOException("No se pudo contactar con el panel de Starlight. Revisa tu conexión.");
            removeIncompatibleMods(instance);
            selectProfile();
            tuneForDevice(ctx, instance);
            return "Modpack v" + prefs.getString(PREF_VERSION, "?") + " (sin conexión)";
        }

        String source = url + "#" + version;
        if (!installed || !source.equals(prefs.getString(PREF_SOURCE, null))) {
            status.post(installed ? "Actualizando modpack a v" + version + "…" : "Instalando modpack v" + version + "…");
            install(ctx, url, instance);
            prefs.edit().putString(PREF_SOURCE, source).putString(PREF_VERSION, version).apply();
        }

        removeIncompatibleMods(instance);
        selectProfile();
        tuneForDevice(ctx, instance);
        ensureServerListed(new File(instance, "servers.dat"));
        return "Modpack v" + version + " listo";
    }

    // ---------- instalación ----------

    private static void install(Context ctx, String url, File instance) throws Exception {
        File mrpack = new File(Tools.DIR_CACHE, "starlight.mrpack");
        try {
            DownloadUtils.downloadFileMonitored(url, mrpack, new byte[8192],
                    new DownloaderProgressWrapper(R.string.modpack_download_downloading_metadata, ProgressLayout.INSTALL_MODPACK));

            // los mods que se quitaron del pack no deben quedarse (darían conflictos)
            deleteRecursive(new File(instance, "mods"));
            if (!instance.isDirectory() && !instance.mkdirs()) throw new IOException("No se pudo crear la carpeta del modpack");

            ModLoader loader = new ModrinthApi().installLocalMrpack(mrpack, instance);
            if (loader == null) throw new IOException("El modpack no indica la versión de Minecraft / Fabric");

            installModLoader(loader);
            createProfile(ctx, loader);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            mrpack.delete();
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
        }
    }

    private static void installModLoader(ModLoader loader) throws Exception {
        final Exception[] error = {null};
        final boolean[] missing = {false};
        Runnable task = loader.getDownloadTask(new ModloaderDownloadListener() {
            @Override public void onDownloadFinished(File downloadedFile) {}
            @Override public void onDataNotAvailable() { missing[0] = true; }
            @Override public void onDownloadError(Exception e) { error[0] = e; }
        });
        if (task == null || loader.requiresGuiInstallation())
            throw new IOException("Este modpack usa un mod loader no soportado en móvil");
        task.run(); // ya estamos en segundo plano
        if (error[0] != null) throw error[0];
        if (missing[0]) throw new IOException("No se encontró Fabric " + loader.modLoaderVersion + " para Minecraft " + loader.minecraftVersion);
    }

    /** Deja un único perfil de Starlight con la clave fija (migra/borra duplicados de versiones anteriores). */
    private static boolean profileExists() {
        try {
            LauncherProfiles.load();
            Map<String, MinecraftProfile> profiles = LauncherProfiles.mainProfileJson.profiles;
            MinecraftProfile keep = profiles.get(PROFILE_KEY);
            boolean changed = false;
            for (String key : new java.util.ArrayList<>(profiles.keySet())) {
                if (key.equals(PROFILE_KEY)) continue;
                MinecraftProfile p = profiles.get(key);
                if (p != null && PROFILE_GAMEDIR.equals(p.gameDir)) {
                    if (keep == null) keep = p;
                    profiles.remove(key);
                    changed = true;
                }
            }
            if (changed) {
                profiles.put(PROFILE_KEY, keep);
                LauncherProfiles.write();
            }
            return keep != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Mods de PC que no funcionan en Android (Sodium se niega a arrancar en Pojav). */
    private static void removeIncompatibleMods(File instance) {
        File[] mods = new File(instance, "mods").listFiles();
        if (mods == null) return;
        for (File f : mods) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.startsWith("sodium") || n.startsWith("iris") || n.startsWith("reeses")
                    || n.contains("sodium-options") || n.contains("sodium_options")) {
                Log.i(TAG, "Quitando mod incompatible con móvil: " + f.getName());
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static void createProfile(Context ctx, ModLoader loader) {
        profileExists(); // migra duplicados a la clave fija
        LauncherProfiles.load();
        MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(PROFILE_KEY);
        if (profile == null) profile = new MinecraftProfile();
        profile.name = "Starlight · Cobblemon";
        profile.gameDir = PROFILE_GAMEDIR;
        profile.lastVersionId = loader.getVersionId();
        String icon = logoBase64(ctx);
        if (icon != null) profile.icon = icon;
        LauncherProfiles.mainProfileJson.profiles.put(PROFILE_KEY, profile);
        LauncherProfiles.write();
    }

    private static void selectProfile() {
        if (!profileExists()) return;
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, PROFILE_KEY).apply();
    }

    private static String logoBase64(Context ctx) {
        try (InputStream in = ctx.getResources().openRawResource(R.drawable.starlight_logo)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- adaptación al dispositivo ----------

    /** Gama del móvil según RAM y núcleos: low / mid / high. */
    public static String deviceTier(Context ctx) {
        int ramMb = Tools.getTotalDeviceMemory(ctx);
        int cores = Runtime.getRuntime().availableProcessors();
        if (ramMb < 4500) return "low";
        if (ramMb < 7500 || cores <= 4) return "mid";
        return "high";
    }

    /** Ajusta RAM, resolución y opciones del juego una vez por dispositivo y versión del modpack. */
    private static void tuneForDevice(Context ctx, File instance) {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        String tier = deviceTier(ctx);
        String key = tier + "|" + prefs.getString(PREF_VERSION, "");
        if (key.equals(prefs.getString(PREF_TUNED, null))) return; // ya ajustado: respeta lo que el jugador cambie luego

        SharedPreferences.Editor edit = prefs.edit();
        int ramMb = Tools.getTotalDeviceMemory(ctx);
        boolean is64 = Build.SUPPORTED_64_BIT_ABIS.length > 0;
        if (is64) {
            // Cobblemon con ~60 mods pide bastante memoria; se deja margen al sistema Android
            int want = tier.equals("low") ? ramMb / 2 : (int) (ramMb * 0.45f);
            int cap = tier.equals("high") ? 4096 : tier.equals("mid") ? 3072 : 2048;
            int alloc = Math.min(want, cap);
            alloc = Math.max(alloc, 1536);
            alloc = Math.min(alloc, ramMb - 1024);
            alloc = Math.max(alloc, 768);
            edit.putInt("allocation", alloc);
        }
        // en gama baja se renderiza a menos resolución (mucho más fluido, poca diferencia visual)
        if (tier.equals("low")) edit.putInt("resolutionRatio", 65);
        edit.putBoolean("sustainedPerformance", !tier.equals("high"));
        edit.apply();
        LauncherPreferences.loadPreferences(ctx);

        try {
            applyOptions(new File(instance, "options.txt"), tier);
        } catch (IOException e) {
            Log.w(TAG, "No se pudo escribir options.txt", e);
        }
        prefs.edit().putString(PREF_TUNED, key).apply();
    }

    private static void applyOptions(File file, String tier) throws IOException {
        Map<String, String> opts = new LinkedHashMap<>();
        if (file.isFile()) {
            for (String line : Tools.read(file).split("\\r?\\n")) {
                int i = line.indexOf(':');
                if (i > 0) opts.put(line.substring(0, i), line.substring(i + 1));
            }
        }
        Map<String, String> preset = new LinkedHashMap<>();
        switch (tier) {
            case "low":
                preset.put("renderDistance", "5"); preset.put("simulationDistance", "5");
                preset.put("graphicsMode", "0"); preset.put("particles", "2");
                preset.put("maxFps", "60"); preset.put("entityShadows", "false");
                preset.put("biomeBlendRadius", "0"); preset.put("mipmapLevels", "0");
                preset.put("renderClouds", "\"false\""); preset.put("ao", "false");
                preset.put("entityDistanceScaling", "0.5");
                break;
            case "mid":
                preset.put("renderDistance", "7"); preset.put("simulationDistance", "6");
                preset.put("graphicsMode", "0"); preset.put("particles", "1");
                preset.put("maxFps", "60"); preset.put("entityShadows", "false");
                preset.put("biomeBlendRadius", "1"); preset.put("mipmapLevels", "2");
                preset.put("renderClouds", "\"fast\""); preset.put("ao", "true");
                preset.put("entityDistanceScaling", "0.75");
                break;
            default:
                preset.put("renderDistance", "10"); preset.put("simulationDistance", "8");
                preset.put("graphicsMode", "1"); preset.put("particles", "0");
                preset.put("maxFps", "90"); preset.put("entityShadows", "true");
                preset.put("biomeBlendRadius", "2"); preset.put("mipmapLevels", "4");
                preset.put("renderClouds", "\"true\""); preset.put("ao", "true");
                preset.put("entityDistanceScaling", "1.0");
        }
        preset.put("enableVsync", "false");
        preset.put("narrator", "0");
        preset.put("tutorialStep", "none");
        preset.put("skipMultiplayerWarning", "true");
        preset.put("joinedFirstServer", "true");
        preset.put("onboardAccessibility", "false");
        opts.putAll(preset);
        if (!opts.containsKey("lang")) opts.put("lang", gameLanguage());

        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : opts.entrySet()) out.append(e.getKey()).append(':').append(e.getValue()).append('\n');
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(out.toString());
        }
    }

    private static String gameLanguage() {
        String lang = Locale.getDefault().getLanguage();
        switch (lang) {
            case "es": return "es_es";
            case "pt": return "pt_br";
            case "fr": return "fr_fr";
            case "de": return "de_de";
            case "it": return "it_it";
            default: return "en_us";
        }
    }

    // ---------- servidor en la lista de multijugador ----------

    /** Crea servers.dat con Starlight si no existe (no toca la lista si el jugador ya tiene una). */
    private static void ensureServerListed(File serversDat) {
        if (serversDat.exists()) return;
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(serversDat))) {
            out.writeByte(10); out.writeUTF("");          // TAG_Compound raíz
            out.writeByte(9); out.writeUTF("servers");    // TAG_List "servers"
            out.writeByte(10); out.writeInt(1);           //   de 1 TAG_Compound
            out.writeByte(8); out.writeUTF("name"); out.writeUTF(SERVER_NAME);
            out.writeByte(8); out.writeUTF("ip"); out.writeUTF(SERVER_IP);
            out.writeByte(1); out.writeUTF("hidden"); out.writeByte(0);
            out.writeByte(1); out.writeUTF("acceptTextures"); out.writeByte(1); // acepta el resource pack del server
            out.writeByte(0);                             //   fin del servidor
            out.writeByte(0);                             // fin de la raíz
        } catch (IOException e) {
            Log.w(TAG, "No se pudo crear servers.dat", e);
        }
    }

    // ---------- utilidades ----------

    private static String fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "StarlightLauncher-Android");
        try (InputStream in = c.getInputStream()) {
            return Tools.read(in);
        } finally {
            c.disconnect();
        }
    }

    private static void deleteRecursive(File f) {
        if (!f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File child : children) deleteRecursive(child);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
