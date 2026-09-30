package com.example.streambox.legacy;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.conscrypt.Conscrypt;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.ui.PlayerView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.security.Security;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class MainActivity extends Activity {
    private static final String BASE_URL = "https://jisuzhuiju.com";
    private static final String IPTV_URL = "https://raw.githubusercontent.com/vbskycn/iptv/refs/heads/master/tv/iptv4.m3u";
    private static final String IPTV_FALLBACK_URL = "https://live.zbds.top/tv/iptv4.m3u";
    private static final String IPTV_4K_URL = "https://gh-proxy.org/raw.githubusercontent.com/suxuang/myIPTV/main/ipv4.m3u";
    private static final String IPTV_4K_FALLBACK_URL = "https://raw.githubusercontent.com/suxuang/myIPTV/refs/heads/main/ipv4.m3u";
    private static final String SOURCE_HD = "hd";
    private static final String SOURCE_4K = "4k";
    private static final String SOURCE_CF = "cf";
    private static final int BLACK = Color.rgb(8, 10, 16);
    private static final int PANEL = Color.rgb(25, 28, 38);
    private static final int PANEL_ACTIVE = Color.rgb(48, 36, 34);
    private static final int BORDER = Color.rgb(58, 63, 78);
    private static final int TEXT = Color.rgb(239, 241, 247);
    private static final int MUTED = Color.rgb(159, 166, 184);
    private static final int ORANGE = Color.rgb(239, 107, 64);
    private static final String[][] CATEGORIES = {
        {"推荐", "/"}, {"电视剧", "/type/dianshiju.html"}, {"电影", "/type/dianying.html"},
        {"动漫", "/type/dongman.html"}, {"综艺", "/type/zongyi.html"}
    };

    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final ExecutorService playlistWorkers = Executors.newFixedThreadPool(3);
    private final ExecutorService probeWorkers = Executors.newFixedThreadPool(12);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Movie> movies = new ArrayList<Movie>();
    private final List<Channel> liveChannels = new ArrayList<Channel>();
    private final List<Channel> shownChannels = new ArrayList<Channel>();
    private final List<Channel> smartChannels = new ArrayList<Channel>();
    private final List<Channel> playbackCandidates = new ArrayList<Channel>();
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8));
    private int route;
    private String currentTitle = "推荐";
    private String currentPath = "/";
    private Detail currentDetail;
    private String currentLiveSource = SOURCE_HD;
    private String playbackTitle = "";
    private int playbackCandidateIndex = -1;
    private int playerSession;
    private boolean autoSwitching;
    private ExoPlayer player;
    private TextView speedView;
    private long lastRxBytes;
    private long lastSpeedAt;
    private final Runnable speedTicker = new Runnable() {
        @Override public void run() {
            if (speedView == null) return;
            long now = System.currentTimeMillis();
            long bytes = TrafficStats.getUidRxBytes(android.os.Process.myUid());
            if (lastRxBytes >= 0 && bytes >= lastRxBytes && now > lastSpeedAt) {
                speedView.setText(formatSpeed((bytes - lastRxBytes) * 1000L / (now - lastSpeedAt)));
            }
            lastRxBytes = bytes;
            lastSpeedAt = now;
            main.postDelayed(this, 1000);
        }
    };
    private final Runnable stallSwitcher = new Runnable() {
        @Override public void run() {
            if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING) switchToNextSource("缓冲超时");
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT < 21) {
            try { installLegacyTls(); }
            catch (Exception error) { Toast.makeText(this, "TLS 初始化失败：" + message(error), Toast.LENGTH_LONG).show(); }
        }
        getWindow().getDecorView().setBackgroundColor(BLACK);
        List<Channel> parserCheck = parseM3u("#EXTM3U\n#EXTINF:-1 tvg-logo=\"logo.png\" group-title=\"4K频道\" http-user-agent=\"AptvPlayer-UA\" http-referer=\"https://example.com/\", CCTV4K\nhttp://example.com/live.m3u8\n");
        if (parserCheck.size() != 1 || !"AptvPlayer-UA".equals(parserCheck.get(0).userAgent) || !"https://example.com/".equals(parserCheck.get(0).referer)) {
            throw new IllegalStateException("M3U parser self-check failed");
        }
        List<Channel> textCheck = parseTextPlaylist("央视,#genre#\nCCTV-2HD,kdsvod://http://example.com/live.m3u8@@Referer=https://example.com/@User-Agent=Test-UA@@\n");
        if (textCheck.size() != 1 || !"央视".equals(textCheck.get(0).group) || !"Test-UA".equals(textCheck.get(0).userAgent)) {
            throw new IllegalStateException("Text playlist parser self-check failed");
        }
        if (!"1.0 MB/s".equals(formatSpeed(1024L * 1024L))) throw new IllegalStateException("Speed formatter self-check failed");
        try {
            smartChannels.addAll(parseTextPlaylist(readAsset("cf_tv_20260930.txt")));
            smartChannels.addAll(parseM3u(getPreferences(MODE_PRIVATE).getString("iptv_playlist_" + SOURCE_HD, "")));
            smartChannels.addAll(parseM3u(getPreferences(MODE_PRIVATE).getString("iptv_playlist_" + SOURCE_4K, "")));
        } catch (Exception error) {
            Toast.makeText(this, "乘风源读取失败：" + message(error), Toast.LENGTH_LONG).show();
        }
        showSplash();
    }

    @Override public void onBackPressed() {
        if (route == 4) showLive();
        else if (route == 3) super.onBackPressed();
        else if (route == 2 && currentDetail != null) showDetail(currentDetail);
        else if (route == 1) showBrowse(false);
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        releasePlayer();
        main.removeCallbacksAndMessages(null);
        workers.shutdownNow();
        playlistWorkers.shutdownNow();
        probeWorkers.shutdownNow();
        super.onDestroy();
    }

    private void showSplash() {
        route = 5;
        FrameLayout root = new FrameLayout(this);
        root.setBackground(appBackground());
        LinearLayout mark = new LinearLayout(this);
        mark.setOrientation(LinearLayout.VERTICAL);
        mark.setGravity(Gravity.CENTER);

        TextView icon = text("▶", 34, Color.WHITE);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(ORANGE, ORANGE, 0));
        mark.addView(icon, new LinearLayout.LayoutParams(dp(76), dp(76)));

        TextView title = text("光影盒子", 42, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(20), 0, dp(4));
        mark.addView(title, titleParams);

        TextView caption = text("电视直播 · 清晰直达", 17, MUTED);
        caption.setGravity(Gravity.CENTER);
        mark.addView(caption, matchWrap());

        FrameLayout.LayoutParams markParams = new FrameLayout.LayoutParams(dp(500), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(mark, markParams);
        setContentView(root);
        mark.setAlpha(0f);
        mark.setScaleX(0.94f);
        mark.setScaleY(0.94f);
        mark.setTranslationY(dp(14));
        mark.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(420).start();
        main.postDelayed(this::showLive, 650);
    }

    private void showBrowse(boolean reload) {
        releasePlayer();
        route = 0;
        LinearLayout root = column(36);
        TextView brand = text("▶  光影盒子 TV", 32, Color.WHITE);
        brand.setTypeface(null, 1);
        root.addView(brand, matchWrap());
        root.addView(modeRow(false), matchWrap());

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索影片名称");
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(Color.LTGRAY);
        search.setTextSize(20);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setBackground(focusBackground());
        search.setPadding(dp(18), dp(12), dp(18), dp(12));
        search.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                String q = v.getText().toString().trim();
                if (!q.isEmpty()) loadCards(root, "搜索：" + q, "/search?keyword=" + encode(q));
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams searchParams = matchWrap();
        searchParams.setMargins(0, dp(18), 0, dp(14));
        root.addView(search, searchParams);

        HorizontalScrollView categoryScroll = new HorizontalScrollView(this);
        categoryScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout categories = new LinearLayout(this);
        categories.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] category : CATEGORIES) {
            Button button = button(category[0]);
            button.setOnClickListener(v -> loadCards(root, category[0], category[1]));
            categories.addView(button, new LinearLayout.LayoutParams(dp(150), dp(58)));
        }
        categoryScroll.addView(categories);
        root.addView(categoryScroll, matchWrap());

        setContentView(root);
        if (reload || movies.isEmpty()) loadCards(root, currentTitle, currentPath);
        else renderGrid(root, currentTitle);
        categories.getChildAt(0).requestFocus();
    }

    private LinearLayout modeRow(boolean live) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(14), 0, dp(8));
        Button moviesButton = button("影视综艺");
        Button liveButton = button("电视直播");
        moviesButton.setOnClickListener(v -> showBrowse(false));
        liveButton.setOnClickListener(v -> showLive());
        row.addView(moviesButton, new LinearLayout.LayoutParams(dp(190), dp(62)));
        row.addView(liveButton, new LinearLayout.LayoutParams(dp(190), dp(62)));
        (live ? liveButton : moviesButton).setTextColor(ORANGE);
        return row;
    }

    private void showLive() {
        releasePlayer();
        route = 3;
        currentLiveSource = getPreferences(MODE_PRIVATE).getString("live_source", SOURCE_HD);
        LinearLayout root = column(36);
        root.addView(liveHeader(), matchWrap());
        TextView subtitle = text("选信号源，再选频道。播放中断时会自动寻找备用线路。", 16, MUTED);
        subtitle.setPadding(dp(70), 0, 0, dp(16));
        root.addView(subtitle, matchWrap());

        LinearLayout sources = new LinearLayout(this);
        sources.setOrientation(LinearLayout.HORIZONTAL);
        Button hd = sourceButton("高清源 · 稳定", SOURCE_HD);
        Button ultra = sourceButton("4K源 · 超清", SOURCE_4K);
        Button cf = sourceButton("乘风源 · 全量", SOURCE_CF);
        sources.addView(hd, spacedButtonParams(210, 60));
        sources.addView(ultra, spacedButtonParams(210, 60));
        sources.addView(cf, spacedButtonParams(210, 60));
        root.addView(sources, matchWrap());

        String sourceText = SOURCE_4K.equals(currentLiveSource)
                ? "4K源：suxuang/myIPTV · 仅显示4K频道，需电视支持对应视频编码"
                : SOURCE_CF.equals(currentLiveSource)
                ? "乘风1.0.1：内置47分组、4223条线路 · 点击频道后自动测速择优"
                : "高清源：vbskycn/iptv · 点击频道后自动从同名线路中测速择优";
        TextView source = text(sourceText, 15, MUTED);
        source.setPadding(dp(4), dp(8), 0, dp(8));
        root.addView(source, matchWrap());
        setContentView(root);
        animateIn(root.getChildAt(0), 0, 10);
        animateIn(root.getChildAt(1), 45, 8);
        animateIn(root.getChildAt(2), 90, 8);
        animateIn(root.getChildAt(3), 135, 6);
        loadLiveSource(root, currentLiveSource);
    }

    private LinearLayout liveHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = text("▶", 22, Color.WHITE);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(ORANGE, ORANGE, 0));
        row.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text("光影盒子", 30, TEXT);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titles.addView(brand, matchWrap());
        titles.addView(text("LIVE TV", 12, MUTED), matchWrap());
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMargins(dp(16), 0, 0, 0);
        row.addView(titles, titleParams);

        TextView live = text("●  正在直播", 15, ORANGE);
        live.setGravity(Gravity.CENTER);
        live.setBackground(rounded(PANEL_ACTIVE, ORANGE, 1));
        live.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.addView(live, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private Button sourceButton(String label, String source) {
        Button button = button(label);
        button.setSelected(source.equals(currentLiveSource));
        button.setOnClickListener(v -> {
            if (source.equals(currentLiveSource)) return;
            getPreferences(MODE_PRIVATE).edit().putString("live_source", source).apply();
            liveChannels.clear();
            showLive();
        });
        return button;
    }

    private void loadLiveSource(LinearLayout root, String source) {
        if (SOURCE_CF.equals(source)) {
            liveChannels.clear();
            liveChannels.addAll(uniqueChannels(smartChannels));
            renderLive(root, "全部");
            return;
        }
        String cacheKey = "iptv_playlist_" + source;
        List<Channel> cached = channelsForSource(parseM3u(getPreferences(MODE_PRIVATE).getString(cacheKey, "")), source);
        final boolean hasCache = !cached.isEmpty();
        if (hasCache) {
            liveChannels.clear();
            liveChannels.addAll(cached);
            renderLive(root, "全部");
        } else {
            showLoading(root);
        }
        playlistWorkers.execute(() -> {
            try {
                String playlist = SOURCE_4K.equals(source)
                        ? fetchFirst(IPTV_4K_URL, IPTV_4K_FALLBACK_URL)
                        : fetchFirst(IPTV_FALLBACK_URL, IPTV_URL);
                List<Channel> parsed = channelsForSource(parseM3u(playlist), source);
                if (parsed.isEmpty()) throw new Exception(SOURCE_4K.equals(source) ? "没有找到4K频道" : "直播源列表为空");
                getPreferences(MODE_PRIVATE).edit().putString(cacheKey, playlist).apply();
                main.post(() -> {
                    if (route != 3 || !source.equals(currentLiveSource)) return;
                    liveChannels.clear();
                    liveChannels.addAll(parsed);
                    smartChannels.addAll(parsed);
                    if (!hasCache) renderLive(root, "全部");
                });
            } catch (Exception error) {
                if (hasCache) return;
                main.post(() -> {
                    if (route != 3 || !source.equals(currentLiveSource)) return;
                    showError(root, new Exception("直播列表加载失败：" + message(error)));
                });
            }
        });
    }

    private List<Channel> channelsForSource(List<Channel> channels, String source) {
        if (!SOURCE_4K.equals(source)) return channels;
        List<Channel> result = new ArrayList<Channel>();
        for (Channel channel : channels) if (channel.group.contains("4K") || channel.name.toUpperCase(Locale.US).contains("4K")) result.add(channel);
        return result;
    }

    private void renderLive(LinearLayout root, String group) {
        while (root.getChildCount() > 4) root.removeViewAt(4);
        HorizontalScrollView groupScroll = new HorizontalScrollView(this);
        groupScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout groups = new LinearLayout(this);
        groups.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels;
        if (SOURCE_4K.equals(currentLiveSource)) labels = new String[]{"全部"};
        else if (SOURCE_CF.equals(currentLiveSource)) {
            LinkedHashSet<String> names = new LinkedHashSet<String>();
            names.add("全部");
            for (Channel channel : liveChannels) names.add(channel.group);
            labels = names.toArray(new String[names.size()]);
        } else labels = new String[]{"全部", "央视频道", "卫视频道", "电影频道"};
        int activeGroup = 0;
        for (int i = 0; i < labels.length; i++) {
            String label = labels[i];
            Button button = button(label);
            button.setSelected(label.equals(group));
            button.setOnClickListener(v -> renderLive(root, label));
            groups.addView(button, spacedButtonParams(160, 56));
            if (label.equals(group)) activeGroup = i;
        }
        groupScroll.addView(groups);
        root.addView(groupScroll, matchWrap());

        shownChannels.clear();
        for (Channel channel : liveChannels) if ("全部".equals(group) || group.equals(channel.group)) shownChannels.add(channel);
        GridView grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setHorizontalSpacing(dp(18));
        grid.setVerticalSpacing(dp(18));
        grid.setSelector(gridSelector());
        grid.setDrawSelectorOnTop(true);
        grid.setPadding(0, dp(10), 0, dp(18));
        grid.setClipToPadding(false);
        grid.setAdapter(new ChannelAdapter());
        addGridMotion(grid);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            Channel channel = shownChannels.get(position);
            playBest(channel);
        });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        grid.setAlpha(0f);
        grid.setTranslationY(dp(14));
        grid.animate().alpha(1f).translationY(0f).setDuration(220).start();
        groups.getChildAt(activeGroup).requestFocus();
    }

    private void loadCards(LinearLayout root, String title, String path) {
        currentTitle = title;
        currentPath = path;
        showLoading(root);
        workers.execute(() -> {
            try {
                List<Movie> result = parseCards(fetch(absolute(path)));
                main.post(() -> {
                    movies.clear();
                    movies.addAll(result);
                    renderGrid(root, title);
                });
            } catch (final Exception error) {
                main.post(() -> showError(root, error));
            }
        });
    }

    private void renderGrid(LinearLayout root, String title) {
        removeContent(root);
        TextView heading = text(title, 28, Color.WHITE);
        heading.setTypeface(null, 1);
        LinearLayout.LayoutParams headingParams = matchWrap();
        headingParams.setMargins(0, dp(18), 0, dp(12));
        root.addView(heading, headingParams);

        GridView grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setHorizontalSpacing(dp(18));
        grid.setVerticalSpacing(dp(20));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(gridSelector());
        grid.setDrawSelectorOnTop(true);
        grid.setAdapter(new MovieAdapter());
        addGridMotion(grid);
        grid.setOnItemClickListener((parent, view, position, id) -> loadDetail(movies.get(position).url));
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        animateIn(grid, 0, 12);
    }

    private void loadDetail(String url) {
        LinearLayout loading = column(36);
        showLoading(loading);
        setContentView(loading);
        workers.execute(() -> {
            try {
                final Detail detail = parseDetail(fetch(absolute(url)), absolute(url));
                main.post(() -> showDetail(detail));
            } catch (final Exception error) {
                main.post(() -> {
                    Toast.makeText(this, message(error), Toast.LENGTH_LONG).show();
                    showBrowse(false);
                });
            }
        });
    }

    private void showDetail(Detail detail) {
        releasePlayer();
        route = 1;
        currentDetail = detail;
        LinearLayout content = column(36);
        Button back = button("‹ 返回");
        back.setOnClickListener(v -> showBrowse(false));
        content.addView(back, new LinearLayout.LayoutParams(dp(150), dp(58)));

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setPadding(0, dp(18), 0, dp(18));
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(PANEL);
        summary.addView(poster, new LinearLayout.LayoutParams(dp(220), dp(305)));
        loadImage(detail.poster, poster);
        LinearLayout meta = column(24);
        TextView name = text(detail.title, 34, Color.WHITE);
        name.setTypeface(null, 1);
        meta.addView(name, matchWrap());
        meta.addView(text(join(" · ", detail.year, detail.region, detail.genre), 20, Color.LTGRAY), matchWrap());
        if (!detail.actors.isEmpty()) meta.addView(text("主演  " + detail.actors, 18, Color.LTGRAY), matchWrap());
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        metaParams.setMargins(dp(28), 0, 0, 0);
        summary.addView(meta, metaParams);
        content.addView(summary, matchWrap());
        if (!detail.description.isEmpty()) content.addView(text(detail.description, 18, Color.LTGRAY), matchWrap());

        TextView sourceTitle = text("播放线路", 26, Color.WHITE);
        sourceTitle.setTypeface(null, 1);
        LinearLayout.LayoutParams sourceTitleParams = matchWrap();
        sourceTitleParams.setMargins(0, dp(22), 0, dp(10));
        content.addView(sourceTitle, sourceTitleParams);
        LinearLayout sourceButtons = new LinearLayout(this);
        sourceButtons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout episodeBox = new LinearLayout(this);
        episodeBox.setOrientation(LinearLayout.VERTICAL);
        for (Source source : detail.sources) {
            Button button = button(source.label);
            button.setOnClickListener(v -> renderEpisodes(episodeBox, source.episodes, detail.title));
            sourceButtons.addView(button, new LinearLayout.LayoutParams(dp(170), dp(58)));
        }
        content.addView(sourceButtons, matchWrap());
        content.addView(episodeBox, matchWrap());
        if (!detail.sources.isEmpty()) renderEpisodes(episodeBox, detail.sources.get(0).episodes, detail.title);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        setContentView(scroll);
        back.requestFocus();
    }

    private void renderEpisodes(LinearLayout box, List<Episode> episodes, String title) {
        box.removeAllViews();
        for (int start = 0; start < episodes.size(); start += 5) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(8), 0, 0);
            for (int i = start; i < Math.min(start + 5, episodes.size()); i++) {
                Episode episode = episodes.get(i);
                Button button = button(episode.label);
                button.setOnClickListener(v -> showPlayer(title + " · " + episode.label, episode.url));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(62), 1);
                params.setMargins(dp(4), 0, dp(4), 0);
                row.addView(button, params);
            }
            box.addView(row, matchWrap());
        }
    }

    private void showPlayer(String title, String url) {
        showPlayer(title, url, "", "");
    }

    private void showPlayer(String title, String url, String streamUserAgent, String streamReferer) {
        showPlayer(title, url, streamUserAgent, streamReferer, C.TIME_UNSET, C.TIME_UNSET);
    }

    private void showPlayer(String title, String url, String streamUserAgent, String streamReferer, long resumePositionMs, long resumeLiveOffsetMs) {
        releasePlayer();
        final int session = ++playerSession;
        boolean live = !url.contains("/vodplay/");
        route = live ? 4 : 2;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BLACK);
        Button back = button("‹ 返回  " + title);
        back.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        back.setOnClickListener(v -> { if (live) showLive(); else showDetail(currentDetail); });
        root.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        PlayerView playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setFocusable(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        FrameLayout stage = new FrameLayout(this);
        stage.addView(playerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView networkSpeed = text("0 KB/s", 13, Color.argb(205, 255, 255, 255));
        networkSpeed.setBackground(rounded(Color.argb(150, 9, 11, 17), Color.argb(90, 255, 255, 255), 1));
        networkSpeed.setPadding(dp(8), dp(4), dp(8), dp(4));
        FrameLayout.LayoutParams speedParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.RIGHT | Gravity.BOTTOM);
        speedParams.setMargins(0, 0, dp(12), dp(10));
        stage.addView(networkSpeed, speedParams);
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        stage.setAlpha(0f);
        stage.animate().alpha(1f).setDuration(260).start();
        startNetworkSpeed(networkSpeed);
        back.requestFocus();
        workers.execute(() -> {
            try {
                String mediaUrl = url;
                if (!live) {
                    Matcher match = Pattern.compile("/vodplay/(\\d+)-([^-]+)-(\\d+)\\.html").matcher(Uri.parse(url).getPath());
                    if (!match.find()) throw new Exception("播放地址格式错误");
                    String api = BASE_URL + "/api/play-url?vodId=" + URLEncoder.encode(match.group(1), "UTF-8")
                            + "&playFrom=" + URLEncoder.encode(match.group(2), "UTF-8")
                            + "&index=" + URLEncoder.encode(match.group(3), "UTF-8");
                    JSONObject response = new JSONObject(fetch(api));
                    if (response.optInt("code") != 200 || response.optString("url").length() == 0) {
                        throw new Exception(response.optString("msg", "播放地址解析失败"));
                    }
                    if (!"native".equals(response.optString("mode", "native"))) throw new Exception("该线路不支持 Android 4.4，请切换线路");
                    mediaUrl = response.getString("url");
                }
                final String playableUrl = mediaUrl;
                main.post(() -> {
                    if (route != (live ? 4 : 2) || session != playerSession) return;
                    Map<String, String> headers = new HashMap<String, String>();
                    if (!streamReferer.isEmpty()) headers.put("Referer", streamReferer);
                    else if (!live) headers.put("Referer", BASE_URL + "/");
                    DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                            .setUserAgent(streamUserAgent.isEmpty() ? "Mozilla/5.0 (Linux; Android 4.4; TV) StreamBox/0.5" : streamUserAgent)
                            .setAllowCrossProtocolRedirects(true)
                            .setDefaultRequestProperties(headers);
                    player = new ExoPlayer.Builder(this)
                            .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http)))
                            .build();
                    autoSwitching = false;
                    player.addListener(new Player.Listener() {
                        private boolean resumeApplied;
                        @Override public void onPlaybackStateChanged(int state) {
                            Log.i("StreamBoxPlayer", "state=" + state);
                            if (session != playerSession) return;
                            main.removeCallbacks(stallSwitcher);
                            if (state == Player.STATE_BUFFERING) main.postDelayed(stallSwitcher, 12000);
                            else if (state == Player.STATE_READY && !resumeApplied) {
                                resumeApplied = true;
                                if (live && resumeLiveOffsetMs != C.TIME_UNSET && player.getDuration() != C.TIME_UNSET) {
                                    player.seekTo(Math.max(0, player.getDuration() - resumeLiveOffsetMs));
                                    Toast.makeText(MainActivity.this, "已切换备用线路并恢复原直播进度", Toast.LENGTH_SHORT).show();
                                } else if (resumePositionMs > 0) {
                                    player.seekTo(resumePositionMs);
                                    Toast.makeText(MainActivity.this, "已切换备用线路并恢复播放位置", Toast.LENGTH_SHORT).show();
                                }
                            } else if (state == Player.STATE_ENDED && live) switchToNextSource("直播已中断");
                        }
                        @Override public void onPlayerError(PlaybackException error) {
                            Log.e("StreamBoxPlayer", "playback failed", error);
                            if (session != playerSession) return;
                            if (live && switchToNextSource("当前线路不可用")) return;
                            String hint = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                                    ? "虚拟机解码器不支持此高清画面，实体电视请实测"
                                    : live ? "直播源暂不可用，请切换频道" : "播放失败，请切换线路";
                            Toast.makeText(MainActivity.this, hint, Toast.LENGTH_LONG).show();
                        }
                    });
                    playerView.setPlayer(player);
                    player.setMediaItem(MediaItem.fromUri(playableUrl));
                    player.prepare();
                    player.play();
                    playerView.requestFocus();
                });
            } catch (Exception error) {
                main.post(() -> Toast.makeText(this, "加载失败：" + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private boolean switchToNextSource(String reason) {
        if (autoSwitching || route != 4 || playbackCandidateIndex + 1 >= playbackCandidates.size()) return false;
        autoSwitching = true;
        main.removeCallbacks(stallSwitcher);
        final long position = player == null ? C.TIME_UNSET : player.getCurrentPosition();
        final long liveOffset = player == null ? C.TIME_UNSET : player.getCurrentLiveOffset();
        final int failedIndex = playbackCandidateIndex;
        final int session = playerSession;
        final List<Channel> remaining = new ArrayList<Channel>(playbackCandidates.subList(failedIndex + 1, playbackCandidates.size()));
        Toast.makeText(this, reason + "，正在检测可用备用线路…", Toast.LENGTH_SHORT).show();
        workers.execute(() -> {
            List<ProbeResult> ranked = rankCandidates(remaining);
            main.post(() -> {
                if (route != 4 || session != playerSession) { autoSwitching = false; return; }
                while (playbackCandidates.size() > failedIndex + 1) playbackCandidates.remove(playbackCandidates.size() - 1);
                for (ProbeResult result : ranked) playbackCandidates.add(result.channel);
                if (playbackCandidates.size() <= failedIndex + 1) { autoSwitching = false; return; }
                playbackCandidateIndex = failedIndex + 1;
                ProbeResult best = ranked.get(0);
                Channel next = best.channel;
                Log.i("StreamBoxPlayer", "auto switch " + playbackCandidateIndex + "/" + playbackCandidates.size() + " reason=" + reason);
                String result = best.elapsedMs == Long.MAX_VALUE ? "尝试下一条备用线路" : "已找到可用备用线路 · " + best.elapsedMs + " ms";
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
                showPlayer(playbackTitle, next.url, next.userAgent, next.referer, position, liveOffset);
            });
        });
        return true;
    }

    private List<Channel> parseM3u(String playlist) {
        List<Channel> result = new ArrayList<Channel>();
        String name = "", group = "其他频道", logo = "", userAgent = "", referer = "";
        Pattern namePattern = Pattern.compile(",\\s*(.+)$");
        Pattern groupPattern = Pattern.compile("group-title=\"([^\"]*)\"");
        Pattern logoPattern = Pattern.compile("tvg-logo=\"([^\"]*)\"");
        Pattern userAgentPattern = Pattern.compile("http-user-agent=\"([^\"]*)\"");
        Pattern refererPattern = Pattern.compile("http-referer=\"([^\"]*)\"");
        for (String raw : playlist.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF")) {
                Matcher names = namePattern.matcher(line);
                Matcher groups = groupPattern.matcher(line);
                Matcher logos = logoPattern.matcher(line);
                Matcher userAgents = userAgentPattern.matcher(line);
                Matcher referers = refererPattern.matcher(line);
                name = names.find() ? names.group(1).trim() : "未命名频道";
                group = groups.find() && !groups.group(1).isEmpty() ? groups.group(1) : "其他频道";
                logo = logos.find() ? logos.group(1) : "";
                userAgent = userAgents.find() ? userAgents.group(1) : "";
                referer = referers.find() ? referers.group(1) : "";
            } else if (!name.isEmpty() && (line.startsWith("http://") || line.startsWith("https://"))) {
                result.add(new Channel(name, group, logo, line.split(";", 2)[0], userAgent, referer));
                name = ""; group = "其他频道"; logo = ""; userAgent = ""; referer = "";
            }
        }
        return result;
    }

    private List<Channel> parseTextPlaylist(String playlist) {
        List<Channel> result = new ArrayList<Channel>();
        String group = "其他频道";
        for (String raw : playlist.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] pair = line.split(",", 2);
            if (pair.length != 2) continue;
            String name = pair[0].trim();
            String value = pair[1].trim();
            if ("#genre#".equalsIgnoreCase(value)) { group = name; continue; }
            String userAgent = "", referer = "";
            int optionAt = value.indexOf("@@");
            if (optionAt >= 0) {
                String options = value.substring(optionAt + 2).replace("@@", "@");
                value = value.substring(0, optionAt);
                for (String option : options.split("@(?=[A-Za-z-]+=)")) {
                    int equals = option.indexOf('=');
                    if (equals < 1) continue;
                    String key = option.substring(0, equals).trim();
                    String optionValue = option.substring(equals + 1).trim();
                    while (optionValue.endsWith("@")) optionValue = optionValue.substring(0, optionValue.length() - 1);
                    if ("user-agent".equalsIgnoreCase(key)) userAgent = optionValue;
                    else if ("referer".equalsIgnoreCase(key)) referer = optionValue;
                }
            }
            if (value.startsWith("kdsvod://")) value = value.substring("kdsvod://".length());
            if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("rtmp://")) {
                result.add(new Channel(name, group, "", value, userAgent, referer));
            }
        }
        return result;
    }

    private String readAsset(String name) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open(name), "UTF-8"));
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) body.append(line).append('\n');
        reader.close();
        return body.toString();
    }

    private List<Channel> uniqueChannels(List<Channel> channels) {
        LinkedHashMap<String, Channel> unique = new LinkedHashMap<String, Channel>();
        for (Channel channel : channels) if (!unique.containsKey(channelKey(channel.name))) unique.put(channelKey(channel.name), channel);
        return new ArrayList<Channel>(unique.values());
    }

    private String channelKey(String name) {
        String upper = name.toUpperCase(Locale.US).replace("高清", "").replace("超清", "").replace("频道", "").trim();
        if (!upper.contains("4K")) {
            Matcher cctv = Pattern.compile("CCTV\\s*[-_]?\\s*(\\d+)").matcher(upper);
            if (cctv.find()) return "CCTV" + cctv.group(1);
        }
        return upper.replaceAll("HD$", "").replaceAll("[^A-Z0-9\\u4E00-\\u9FA5]", "");
    }

    private void playBest(Channel selected) {
        String key = channelKey(selected.name);
        List<Channel> candidates = candidatesForKey(key);
        playbackCandidates.clear();
        playbackCandidates.add(selected);
        for (Channel candidate : candidates) if (!candidate.url.equals(selected.url)) playbackCandidates.add(candidate);
        playbackCandidateIndex = 0;
        playbackTitle = "直播 · " + selected.name;
        showPlayer(playbackTitle, selected.url, selected.userAgent, selected.referer);
    }

    private List<Channel> candidatesForKey(String key) {
        LinkedHashMap<String, Channel> unique = new LinkedHashMap<String, Channel>();
        for (Channel channel : smartChannels) if (key.equals(channelKey(channel.name))) unique.put(channel.url, channel);
        for (Channel channel : liveChannels) if (key.equals(channelKey(channel.name))) unique.put(channel.url, channel);
        return new ArrayList<Channel>(unique.values());
    }

    private List<ProbeResult> rankCandidates(List<Channel> candidates) {
        CompletionService<ProbeResult> probes = new ExecutorCompletionService<ProbeResult>(probeWorkers);
        List<Future<ProbeResult>> pending = new ArrayList<Future<ProbeResult>>();
        for (Channel channel : candidates) if (channel.url.startsWith("http://") || channel.url.startsWith("https://")) pending.add(probes.submit(() -> probe(channel)));
        List<ProbeResult> ranked = new ArrayList<ProbeResult>();
        try {
            for (int i = 0; i < pending.size(); i++) {
                try { ranked.add(probes.take().get()); }
                catch (Exception ignored) { }
            }
        } finally {
            for (Future<ProbeResult> request : pending) request.cancel(true);
        }
        Collections.sort(ranked, new Comparator<ProbeResult>() {
            @Override public int compare(ProbeResult left, ProbeResult right) { return Long.compare(left.elapsedMs, right.elapsedMs); }
        });
        LinkedHashSet<String> included = new LinkedHashSet<String>();
        for (ProbeResult result : ranked) included.add(result.channel.url);
        for (Channel candidate : candidates) if (!included.contains(candidate.url)) ranked.add(new ProbeResult(candidate, Long.MAX_VALUE));
        return ranked;
    }

    private ProbeResult probe(Channel channel) throws Exception {
        long start = System.currentTimeMillis();
        HttpURLConnection connection = (HttpURLConnection) new URL(channel.url).openConnection();
        connection.setConnectTimeout(1800);
        connection.setReadTimeout(2200);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", channel.userAgent.isEmpty() ? "Mozilla/5.0 (Linux; Android 4.4; TV) StreamBox/0.5" : channel.userAgent);
        if (!channel.referer.isEmpty()) connection.setRequestProperty("Referer", channel.referer);
        int status = connection.getResponseCode();
        if (status < 200 || status > 399) { connection.disconnect(); throw new Exception("HTTP " + status); }
        InputStream stream = connection.getInputStream();
        if (stream.read() < 0) { stream.close(); connection.disconnect(); throw new Exception("empty stream"); }
        stream.close();
        connection.disconnect();
        return new ProbeResult(channel, System.currentTimeMillis() - start);
    }

    private void releasePlayer() {
        main.removeCallbacks(speedTicker);
        main.removeCallbacks(stallSwitcher);
        speedView = null;
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private List<Movie> parseCards(String html) {
        Pattern pattern = Pattern.compile("<a\\s+href=[\\\"'](/detail/(\\d+)\\.html)[\\\"'][^>]*>\\s*<div\\s+class=[\\\"']vod-card[\\\"']>.*?<img\\s+src=[\\\"']([^\\\"']+)[\\\"']\\s+alt=[\\\"']([^\\\"']+)[\\\"']", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(html);
        List<Movie> result = new ArrayList<Movie>();
        while (matcher.find()) result.add(new Movie(decode(matcher.group(4)).replace("封面图片", ""), absolute(matcher.group(1)), decode(matcher.group(3))));
        return result;
    }

    private Detail parseDetail(String html, String url) throws Exception {
        Matcher jsonMatch = Pattern.compile("<script\\s+type=[\\\"']application/ld\\+json[\\\"']>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (!jsonMatch.find()) throw new Exception("详情元数据不存在");
        JSONObject root = new JSONObject(jsonMatch.group(1));
        JSONObject media = null;
        JSONArray graph = root.optJSONArray("@graph");
        if (graph != null) for (int i = 0; i < graph.length(); i++) {
            JSONObject node = graph.optJSONObject(i);
            if (node != null && isMedia(node.optString("@type"))) { media = node; break; }
        }
        if (media == null && isMedia(root.optString("@type"))) media = root;
        if (media == null) throw new Exception("无法识别详情页");

        LinkedHashMap<String, List<Episode>> grouped = new LinkedHashMap<String, List<Episode>>();
        Matcher episodes = Pattern.compile("<a\\s+href=[\\\"'](/vodplay/[^\\\"']+)[\\\"'][^>]*class=[\\\"'][^\\\"']*episode-btn[^\\\"']*[\\\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        Pattern codePattern = Pattern.compile("/vodplay/\\d+-([^-]+)-\\d+\\.html");
        while (episodes.find()) {
            String path = episodes.group(1);
            Matcher code = codePattern.matcher(path);
            String key = code.find() ? code.group(1) : "default";
            if (!grouped.containsKey(key)) grouped.put(key, new ArrayList<Episode>());
            grouped.get(key).add(new Episode(stripTags(episodes.group(2)), absolute(path)));
        }
        List<String> names = new ArrayList<String>();
        Matcher tabs = Pattern.compile("class=[\\\"'][^\\\"']*source-tab[^\\\"']*[\\\"'][^>]*>(.*?)</button>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (tabs.find()) names.add(stripTags(tabs.group(1)));
        List<Source> sources = new ArrayList<Source>();
        int index = 0;
        for (Map.Entry<String, List<Episode>> entry : grouped.entrySet()) {
            Source source = new Source(index < names.size() ? names.get(index) : entry.getKey(), entry.getValue());
            if (entry.getKey().contains("m3u8")) sources.add(0, source);
            else sources.add(source);
            index++;
        }
        return new Detail(media.optString("name"), media.optString("description"), media.optString("image", media.optString("thumbnailUrl")), media.optString("datePublished"), jsonText(media.opt("genre")), jsonText(media.opt("actor")), media.optJSONObject("countryOfOrigin") == null ? "" : media.optJSONObject("countryOfOrigin").optString("name"), sources);
    }

    private String fetch(String address) throws Exception {
        return fetch(address, 12000, 20000);
    }

    private String fetch(String address, int connectTimeout, int readTimeout) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 4.4; TV) StreamBox/0.1");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        if (connection.getResponseCode() < 200 || connection.getResponseCode() > 299) throw new Exception("网络请求失败：" + connection.getResponseCode());
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) body.append(line).append('\n');
        reader.close();
        return body.toString();
    }

    private String fetchFirst(String... addresses) throws Exception {
        CompletionService<String> requests = new ExecutorCompletionService<String>(playlistWorkers);
        List<Future<String>> pending = new ArrayList<Future<String>>();
        for (String address : addresses) pending.add(requests.submit(() -> fetch(address, 6000, 12000)));
        Exception last = new Exception("直播源连接失败");
        try {
            for (int i = 0; i < addresses.length; i++) {
                try { return requests.take().get(); }
                catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    last = cause instanceof Exception ? (Exception) cause : new Exception(cause);
                }
            }
        } finally {
            for (Future<String> request : pending) request.cancel(true);
        }
        throw last;
    }

    private void startNetworkSpeed(TextView view) {
        main.removeCallbacks(speedTicker);
        speedView = view;
        lastRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        lastSpeedAt = System.currentTimeMillis();
        main.postDelayed(speedTicker, 1000);
    }

    private String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond >= 1024L * 1024L) return String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1048576.0);
        return Math.max(0, bytesPerSecond / 1024L) + " KB/s";
    }

    private void installLegacyTls() throws Exception {
        Security.insertProviderAt(Conscrypt.newProvider(), 1);
        TrustManagerFactory systemTrust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        systemTrust.init((KeyStore) null);
        CertificateFactory certificates = CertificateFactory.getInstance("X.509");
        Certificate root = certificates.generateCertificate(getResources().openRawResource(com.example.streambox.legacy.R.raw.isrg_root_x1));
        Certificate sectigo = certificates.generateCertificate(getResources().openRawResource(com.example.streambox.legacy.R.raw.sectigo_root_r46_cross));
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("isrg-root-x1", root);
        store.setCertificateEntry("sectigo-root-r46", sectigo);
        TrustManagerFactory addedTrust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        addedTrust.init(store);
        X509TrustManager system = x509(systemTrust.getTrustManagers());
        X509TrustManager added = x509(addedTrust.getTrustManagers());
        X509TrustManager combined = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return system.getAcceptedIssuers(); }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { system.checkClientTrusted(chain, authType); }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try { system.checkServerTrusted(chain, authType); }
                catch (CertificateException ignored) { added.checkServerTrusted(chain, authType); }
            }
        };
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        context.init(null, new TrustManager[]{combined}, null);
        HttpsURLConnection.setDefaultSSLSocketFactory(new Tls12SocketFactory(context.getSocketFactory()));
    }

    private X509TrustManager x509(TrustManager[] managers) {
        for (TrustManager manager : managers) if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        throw new IllegalStateException("X509 trust manager unavailable");
    }

    private void loadImage(final String url, final ImageView view) {
        view.setTag(url);
        Bitmap cached = images.get(url);
        if (cached != null) { view.setImageBitmap(cached); return; }
        workers.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", "StreamBox TV/0.1");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap bitmap = BitmapFactory.decodeStream(connection.getInputStream(), null, options);
                if (bitmap != null) images.put(url, bitmap);
                main.post(() -> { if (url.equals(view.getTag()) && bitmap != null) view.setImageBitmap(bitmap); });
            } catch (Exception ignored) { }
        });
    }

    private LinearLayout column(int paddingDp) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(paddingDp), dp(24), dp(paddingDp), dp(20));
        layout.setBackground(appBackground());
        return layout;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(17);
        button.setTextColor(buttonTextColors());
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setFocusable(true);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackground(focusBackground());
        button.setOnFocusChangeListener((view, focused) -> view.animate()
                .scaleX(focused ? 1.045f : 1f)
                .scaleY(focused ? 1.045f : 1f)
                .translationY(focused ? -dp(3) : 0)
                .setDuration(150)
                .start());
        button.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) return false;
            float scale = event.getAction() == KeyEvent.ACTION_DOWN ? 0.98f : view.hasFocus() ? 1.045f : 1f;
            view.animate().scaleX(scale).scaleY(scale).setDuration(80).start();
            return false;
        });
        return button;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.15f);
        return view;
    }

    private void showLoading(LinearLayout root) {
        removeContent(root);
        LinearLayout state = new LinearLayout(this);
        state.setOrientation(LinearLayout.VERTICAL);
        state.setGravity(Gravity.CENTER);
        ProgressBar progress = new ProgressBar(this);
        state.addView(progress, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView label = text("正在准备内容…", 17, MUTED);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = matchWrap();
        labelParams.setMargins(0, dp(14), 0, 0);
        state.addView(label, labelParams);
        root.addView(state, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        animateIn(state, 0, 8);
    }

    private void showError(LinearLayout root, Exception error) {
        removeContent(root);
        TextView message = text("暂时无法载入\n" + message(error), 20, Color.rgb(255, 154, 137));
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(dp(6), 1f);
        root.addView(message, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        animateIn(message, 0, 8);
    }

    private void removeContent(LinearLayout root) {
        while (root.getChildCount() > 4) root.removeViewAt(4);
    }

    private StateListDrawable focusBackground() {
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[]{android.R.attr.state_pressed}, rounded(ORANGE, ORANGE, 2));
        state.addState(new int[]{android.R.attr.state_focused}, rounded(PANEL_ACTIVE, ORANGE, 3));
        state.addState(new int[]{android.R.attr.state_selected}, rounded(PANEL_ACTIVE, ORANGE, 1));
        state.addState(new int[]{}, rounded(PANEL, BORDER, 1));
        return state;
    }

    private StateListDrawable gridSelector() {
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[]{android.R.attr.state_pressed}, rounded(Color.argb(55, 239, 107, 64), ORANGE, 4));
        state.addState(new int[]{android.R.attr.state_focused}, rounded(Color.argb(38, 239, 107, 64), ORANGE, 4));
        state.addState(new int[]{android.R.attr.state_selected}, rounded(Color.argb(38, 239, 107, 64), ORANGE, 4));
        state.addState(new int[]{}, rounded(Color.TRANSPARENT, Color.TRANSPARENT, 0));
        return state;
    }

    private GradientDrawable rounded(int fill, int stroke, int width) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(width), stroke);
        return drawable;
    }

    private GradientDrawable appBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(15, 18, 28), BLACK, Color.rgb(7, 9, 14)});
    }

    private ColorStateList buttonTextColors() {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_pressed},
                        new int[]{android.R.attr.state_focused},
                        new int[]{android.R.attr.state_selected},
                        new int[]{}
                },
                new int[]{Color.WHITE, Color.WHITE, ORANGE, TEXT});
    }

    private LinearLayout.LayoutParams spacedButtonParams(int width, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(width), dp(height));
        params.setMargins(0, 0, dp(12), 0);
        return params;
    }

    private void animateIn(View view, long delayMs, int offsetDp) {
        view.setAlpha(0f);
        view.setTranslationY(dp(offsetDp));
        view.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(240).start();
    }

    private void addGridMotion(GridView grid) {
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private View previous;
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (previous != null && previous != view) previous.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(120).start();
                if (view != null) view.animate().scaleX(1.035f).scaleY(1.035f).translationY(-dp(4)).setDuration(140).start();
                previous = view;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {
                if (previous != null) previous.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(120).start();
                previous = null;
            }
        });
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private String absolute(String value) { return value.startsWith("http") ? value : value.startsWith("/") ? BASE_URL + value : BASE_URL + "/" + value; }
    private String encode(String value) { try { return URLEncoder.encode(value, "UTF-8"); } catch (Exception e) { return value; } }
    private String stripTags(String value) { return decode(value.replaceAll("<[^>]+>", "").trim()); }
    private String decode(String value) { return value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " "); }
    private boolean isMedia(String type) { return "TVSeries".equals(type) || "Movie".equals(type) || "CreativeWork".equals(type); }
    private String message(Exception error) { return error.getMessage() == null ? "加载失败" : error.getMessage(); }

    private String jsonText(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (!(value instanceof JSONArray)) return value.toString();
        JSONArray array = (JSONArray) value;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            String part = item instanceof JSONObject ? ((JSONObject) item).optString("name") : String.valueOf(item);
            if (!part.isEmpty()) { if (text.length() > 0) text.append(", "); text.append(part); }
        }
        return text.toString();
    }

    private String join(String separator, String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) if (value != null && !value.isEmpty()) { if (result.length() > 0) result.append(separator); result.append(value); }
        return result.toString();
    }

    private class MovieAdapter extends BaseAdapter {
        @Override public int getCount() { return movies.size(); }
        @Override public Movie getItem(int position) { return movies.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout card = recycled instanceof LinearLayout ? (LinearLayout) recycled : new LinearLayout(MainActivity.this);
            card.removeAllViews();
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(7), dp(7), dp(7), dp(10));
            Movie movie = getItem(position);
            ImageView poster = new ImageView(MainActivity.this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setBackgroundColor(PANEL);
            card.addView(poster, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)));
            TextView title = text(movie.title, 18, Color.WHITE);
            title.setMaxLines(2);
            title.setPadding(0, dp(7), 0, 0);
            card.addView(title, matchWrap());
            loadImage(movie.poster, poster);
            return card;
        }
    }

    private class ChannelAdapter extends BaseAdapter {
        @Override public int getCount() { return shownChannels.size(); }
        @Override public Channel getItem(int position) { return shownChannels.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout card = recycled instanceof LinearLayout ? (LinearLayout) recycled : new LinearLayout(MainActivity.this);
            card.removeAllViews();
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(10), dp(10), dp(10), dp(10));
            card.setBackground(rounded(PANEL, BORDER, 1));
            Channel channel = getItem(position);
            ImageView logo = new ImageView(MainActivity.this);
            TextView name = text(channel.name, 18, TEXT);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            if (!channel.logo.isEmpty()) {
                logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                logo.setBackgroundColor(Color.TRANSPARENT);
                card.addView(logo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100)));
                name.setPadding(0, dp(8), 0, 0);
                card.addView(name, matchWrap());
                loadImage(channel.logo, logo);
            } else {
                name.setPadding(dp(8), dp(12), dp(8), dp(12));
                card.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));
            }
            return card;
        }
    }

    private static class Movie { final String title, url, poster; Movie(String title, String url, String poster) { this.title = title; this.url = url; this.poster = poster; } }
    private static class Channel {
        final String name, group, logo, url, userAgent, referer;
        Channel(String name, String group, String logo, String url, String userAgent, String referer) {
            this.name = name; this.group = group; this.logo = logo; this.url = url; this.userAgent = userAgent; this.referer = referer;
        }
    }
    private static class ProbeResult {
        final Channel channel; final long elapsedMs;
        ProbeResult(Channel channel, long elapsedMs) { this.channel = channel; this.elapsedMs = elapsedMs; }
    }
    private static class Episode { final String label, url; Episode(String label, String url) { this.label = label; this.url = url; } }
    private static class Source { final String label; final List<Episode> episodes; Source(String label, List<Episode> episodes) { this.label = label; this.episodes = episodes; } }
    private static class Detail {
        final String title, description, poster, year, genre, actors, region;
        final List<Source> sources;
        Detail(String title, String description, String poster, String year, String genre, String actors, String region, List<Source> sources) {
            this.title = title; this.description = description; this.poster = poster; this.year = year; this.genre = genre; this.actors = actors; this.region = region; this.sources = sources;
        }
    }

    private static class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        Tls12SocketFactory(SSLSocketFactory delegate) { this.delegate = delegate; }
        private java.net.Socket enable(java.net.Socket socket) {
            if (socket instanceof SSLSocket) ((SSLSocket) socket).setEnabledProtocols(new String[]{"TLSv1.2"});
            return socket;
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public java.net.Socket createSocket(java.net.Socket s, String h, int p, boolean a) throws java.io.IOException { return enable(delegate.createSocket(s, h, p, a)); }
        @Override public java.net.Socket createSocket(String h, int p) throws java.io.IOException { return enable(delegate.createSocket(h, p)); }
        @Override public java.net.Socket createSocket(String h, int p, java.net.InetAddress l, int lp) throws java.io.IOException { return enable(delegate.createSocket(h, p, l, lp)); }
        @Override public java.net.Socket createSocket(java.net.InetAddress h, int p) throws java.io.IOException { return enable(delegate.createSocket(h, p)); }
        @Override public java.net.Socket createSocket(java.net.InetAddress h, int p, java.net.InetAddress l, int lp) throws java.io.IOException { return enable(delegate.createSocket(h, p, l, lp)); }
    }
}
