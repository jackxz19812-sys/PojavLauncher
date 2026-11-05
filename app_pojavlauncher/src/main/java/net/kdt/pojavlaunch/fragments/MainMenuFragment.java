package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.openPath;
import static net.kdt.pojavlaunch.Tools.shareLog;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.starlight.StarlightModpack;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.File;

public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private mcVersionSpinner mVersionSpinner;

    public MainMenuFragment(){
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mNewsButton = view.findViewById(R.id.news_button);
        Button mDiscordButton = view.findViewById(R.id.discord_button);
        Button mCustomControlButton = view.findViewById(R.id.custom_control_button);
        Button mInstallJarButton = view.findViewById(R.id.install_jar_button);
        Button mShareLogsButton = view.findViewById(R.id.share_logs_button);
        Button mOpenDirectoryButton = view.findViewById(R.id.open_files_button);

        ImageButton mEditProfileButton = view.findViewById(R.id.edit_profile_button);
        Button mPlayButton = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);

        // Starlight: el botón Web abre la web del servidor
        mNewsButton.setOnClickListener(v -> Tools.openURL(requireActivity(), "https://starlight.sarl/"));
        mDiscordButton.setOnClickListener(v -> Tools.openURL(requireActivity(), getString(R.string.discord_invite)));
        mCustomControlButton.setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation(false));
        mInstallJarButton.setOnLongClickListener(v->{
            runInstallerWithConfirmation(true);
            return true;
        });
        mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));

        mPlayButton.setOnClickListener(v -> ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true));

        mShareLogsButton.setOnClickListener((v) -> shareLog(requireContext()));

        mOpenDirectoryButton.setOnClickListener((v)-> {
            Tools.switchDemo(Tools.isDemoProfile(v.getContext())); // avoid switching accounts being able to access
            if(Tools.isDemoProfile(v.getContext())){
                Toast.makeText(v.getContext(), R.string.toast_not_available_demo, Toast.LENGTH_LONG).show();
                return;
            }

            openPath(v.getContext(), getCurrentProfileDirectory(), false);
        });


        mNewsButton.setOnLongClickListener((v)->{
            Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
            return true;
        });

        loadStarlightData(view);
        startModpackSync(view);
    }

    // Starlight: instala/actualiza el modpack del servidor y deja el perfil listo para JUGAR
    private void startModpackSync(View root) {
        TextView status = root.findViewById(R.id.sl_modpack_status);
        if (StarlightModpack.isRunning()) {
            if (status != null) status.setText("Instalando modpack… (progreso abajo)");
            return;
        }
        StarlightModpack.sync(requireContext(), new StarlightModpack.Listener() {
            @Override
            public void onStatus(String text) {
                if (!isAdded() || status == null) return;
                status.setText(text);
                status.setTextColor(ContextCompat.getColor(requireContext(), R.color.secondary_text));
            }

            @Override
            public void onDone(boolean ok, String message) {
                if (!isAdded()) return;
                if (status != null) {
                    status.setText(ok ? "✓ " + message : "⚠ " + message);
                    status.setTextColor(ok ? 0xFFD8B4FE : 0xFFF87171);
                }
                mVersionSpinner.reloadProfiles();
                if (!ok) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private File getCurrentProfileDirectory() {
        String currentProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        if(!Tools.isValidString(currentProfile)) return new File(Tools.DIR_GAME_NEW);
        LauncherProfiles.load();
        MinecraftProfile profileObject = LauncherProfiles.mainProfileJson.profiles.get(currentProfile);
        if(profileObject == null) return new File(Tools.DIR_GAME_NEW);
        return Tools.getGameDirPath(profileObject);
    }

    @Override
    public void onResume() {
        super.onResume();
        mVersionSpinner.reloadProfiles();
        updateWelcome();
    }

    // Starlight: saludo con el nombre de la cuenta activa
    private void updateWelcome() {
        View root = getView();
        if (root == null) return;
        TextView welcome = root.findViewById(R.id.sl_welcome);
        if (welcome == null) return;
        try {
            MinecraftAccount account = PojavProfile.getCurrentProfileContent(requireContext(), null);
            if (account != null && Tools.isValidString(account.username)) {
                welcome.setText("Bienvenido de vuelta, " + account.username);
                return;
            }
        } catch (Exception ignored) {}
        welcome.setText("Bienvenido de vuelta, entrenador");
    }

    // ---------- Starlight: novedades del panel y jugadores en línea ----------
    private static final String SL_MANIFEST = "https://starlight.sarl/api/launcher/manifest";
    // el lobby solo cuenta a los suyos: se suman también los de la modalidad
    private static final String[] SL_SERVERS = {"starlight.sarl", "va107.holy.gg:19242"};

    private void loadStarlightData(View root) {
        new Thread(() -> {
            int players = 0;
            boolean anyOnline = false;
            Set<String> seen = new HashSet<>();
            for (String server : SL_SERVERS) {
                try {
                    JSONObject status = new JSONObject(slFetch("https://api.mcsrvstat.us/3/" + server));
                    if (!status.optBoolean("online")) continue;
                    anyOnline = true;
                    JSONObject p = status.optJSONObject("players");
                    if (p == null) continue;
                    players += p.optInt("online");
                    JSONArray list = p.optJSONArray("list");
                    if (list != null) for (int i = 0; i < list.length(); i++) {
                        JSONObject pl = list.optJSONObject(i);
                        // el mismo jugador visto en dos servidores solo cuenta una vez
                        if (pl != null && !seen.add(pl.optString("name"))) players--;
                    }
                } catch (Exception ignored) {}
            }
            JSONArray news = null;
            try { news = new JSONObject(slFetch(SL_MANIFEST)).optJSONArray("news"); } catch (Exception ignored) {}

            final int finalPlayers = players;
            final boolean finalOnline = anyOnline;
            final JSONArray finalNews = news;
            root.post(() -> {
                if (!isAdded()) return;
                showServerStatus(root, finalOnline, finalPlayers);
                showNews(root, finalNews);
            });
        }, "StarlightData").start();
    }

    private static String slFetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "StarlightLauncher-Android");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private void showServerStatus(View root, boolean online, int players) {
        TextView status = root.findViewById(R.id.sl_server_status);
        if (status == null) return;
        if (online) {
            status.setText("● " + players + (players == 1 ? " jugador en línea" : " jugadores en línea"));
            status.setTextColor(0xFF4ADE80);
        } else {
            status.setText("● Servidor sin conexión");
            status.setTextColor(0xFFF87171);
        }
    }

    private void showNews(View root, JSONArray news) {
        LinearLayout list = root.findViewById(R.id.sl_news_list);
        View label = root.findViewById(R.id.sl_news_label);
        if (list == null) return;
        list.removeAllViews();
        if (news == null || news.length() == 0) {
            if (label != null) label.setVisibility(View.GONE);
            return;
        }
        if (label != null) label.setVisibility(View.VISIBLE);

        Context ctx = requireContext();
        Resources res = getResources();
        Typeface bold = ResourcesCompat.getFont(ctx, R.font.noto_sans_bold);
        int pad = res.getDimensionPixelSize(R.dimen._12sdp);
        int gap = res.getDimensionPixelSize(R.dimen._4sdp);

        for (int i = 0; i < Math.min(news.length(), 3); i++) {
            JSONObject item = news.optJSONObject(i);
            if (item == null) continue;

            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(ResourcesCompat.getDrawable(res, R.drawable.sl_card, null));
            card.setPadding(pad, pad * 2 / 3, pad, pad * 2 / 3);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(gap, gap, gap, gap);
            card.setLayoutParams(lp);

            TextView tag = new TextView(ctx);
            tag.setText(item.optString("tag", "Novedad").toUpperCase());
            tag.setTypeface(bold);
            tag.setLetterSpacing(0.2f);
            tag.setTextColor(ContextCompat.getColor(ctx, R.color.starlight_accent));
            tag.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimensionPixelSize(R.dimen._7ssp));

            TextView title = new TextView(ctx);
            title.setText(item.optString("title"));
            title.setTypeface(bold);
            title.setTextColor(ContextCompat.getColor(ctx, R.color.primary_text));
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimensionPixelSize(R.dimen._11ssp));

            TextView body = new TextView(ctx);
            body.setText(item.optString("text"));
            body.setMaxLines(3);
            body.setEllipsize(TextUtils.TruncateAt.END);
            body.setTextColor(ContextCompat.getColor(ctx, R.color.secondary_text));
            body.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimensionPixelSize(R.dimen._9ssp));

            card.addView(tag);
            card.addView(title);
            card.addView(body);

            String url = item.optString("url", "");
            if (url.startsWith("http")) {
                card.setClickable(true);
                card.setOnClickListener(v -> Tools.openURL(requireActivity(), url));
            }
            list.addView(card);
        }
    }

    private void runInstallerWithConfirmation(boolean isCustomArgs) {
        // avoid using custom installers to install a version
        if(Tools.isLocalProfile(requireContext()) || Tools.isDemoProfile(requireContext())){
            Toast.makeText(requireContext(), R.string.toast_not_available_demo, Toast.LENGTH_LONG).show();
            return;
        }

        if (ProgressKeeper.getTaskCount() == 0)
            Tools.installMod(requireActivity(), isCustomArgs);
        else
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }
}
