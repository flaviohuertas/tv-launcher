package io.freemytv;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Universal Search for Free My TV: Option B Split-Screen UX/UI (40/60) with
 * Active Filtering Chips (Em alta, Filmes, Séries), Rich Results with Posters & IMDb,
 * and Full Dynamic Synopsis Panel with 1-Click Launch. Exclusively for Movies & Series.
 */
final class SearchDialog {
    private static final int FILTER_ALL = 0;
    private static final int FILTER_MOVIES = 1;
    private static final int FILTER_SHOWS = 2;

    private static final ExecutorService searchIo = Executors.newSingleThreadExecutor();
    private static final ExecutorService imageIo = Executors.newFixedThreadPool(4);
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static final Map<String, Bitmap> posterCache = new LinkedHashMap<String, Bitmap>(50, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 50;
        }
    };

    private static final Map<String, Bitmap> backdropCache = new LinkedHashMap<String, Bitmap>(30, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 30;
        }
    };

    private SearchDialog() {}

    static void show(Context context) {
        Dialog dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0xF408080A));
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        }

        float density = context.getResources().getDisplayMetrics().density;
        int padH = Math.round(44 * density);
        int padV = Math.round(24 * density);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padH, padV, padH, padV);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 1. Search Bar Header
        LinearLayout searchBar = new LinearLayout(context);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText field = new EditText(context);
        field.setHint(Ui.tr("Search movies and shows…"));
        field.setHintTextColor(0x73FFFFFF);
        field.setTextColor(0xFFFFFFFF);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);

        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(0x24FFFFFF);
        fieldBg.setCornerRadius(14 * density);
        fieldBg.setStroke(Math.round(1 * density), 0x24FFFFFF);
        field.setBackground(fieldBg);
        int fPadH = Math.round(22 * density), fPadV = Math.round(12 * density);
        field.setPadding(fPadH, fPadV, fPadH, fPadV);

        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        searchBar.addView(field, fieldParams);
        root.addView(searchBar);

        // 2. Quick Filter Chips Bar
        LinearLayout chipsBar = new LinearLayout(context);
        chipsBar.setOrientation(LinearLayout.HORIZONTAL);
        chipsBar.setGravity(Gravity.CENTER_VERTICAL);
        chipsBar.setPadding(0, Math.round(14 * density), 0, Math.round(12 * density));

        // Right Detail Panel Holder
        DetailPanelHolder detailHolder = buildDetailPanel(context, density, dialog);

        // 3. Split-Screen Body (Left Results 40%, Right Detail 60%)
        LinearLayout splitLayout = new LinearLayout(context);
        splitLayout.setOrientation(LinearLayout.HORIZONTAL);
        splitLayout.setWeightSum(100f);
        splitLayout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Left Results ScrollView
        ScrollView leftScroll = new ScrollView(context);
        leftScroll.setFillViewport(true);
        leftScroll.setVerticalScrollBarEnabled(false);

        LinearLayout results = new LinearLayout(context);
        results.setOrientation(LinearLayout.VERTICAL);
        leftScroll.addView(results, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 40f);
        leftParams.rightMargin = Math.round(28 * density);
        splitLayout.addView(leftScroll, leftParams);

        // Right Dynamic Detail Panel (60%)
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 60f);
        splitLayout.addView(detailHolder.container, rightParams);

        // Filter state tracking
        final int[] currentFilter = new int[]{FILTER_ALL};
        final List<TextView> chipViews = new ArrayList<>();
        final List<Integer> chipFilterIds = new ArrayList<>();

        final int[] filterIds = new int[]{FILTER_ALL, FILTER_MOVIES, FILTER_SHOWS};
        final String[] filterLabels = new String[]{
                "🔥 " + Ui.tr("Trending"),
                "🎬 " + Ui.tr("Movies"),
                "📺 " + Ui.tr("Shows")
        };

        for (int i = 0; i < filterIds.length; i++) {
            final int fId = filterIds[i];
            TextView chip = addFilterChip(chipsBar, context, filterLabels[i], density);
            chipViews.add(chip);
            chipFilterIds.add(fId);

            chip.setOnFocusChangeListener((v, hasFocus) -> {
                updateChipsVisuals(chipViews, chipFilterIds, currentFilter[0], density);
            });

            chip.setOnClickListener(v -> {
                currentFilter[0] = fId;
                updateChipsVisuals(chipViews, chipFilterIds, currentFilter[0], density);
                String q = field.getText().toString().trim();
                applyFilterAndSearch(context, q, currentFilter[0], results, dialog, detailHolder, density);
            });
        }

        // Set initial chip state
        updateChipsVisuals(chipViews, chipFilterIds, currentFilter[0], density);

        root.addView(chipsBar);
        root.addView(splitLayout);
        dialog.setContentView(root);

        // Debounced search runner
        final Runnable[] searchTask = new Runnable[1];
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (searchTask[0] != null) main.removeCallbacks(searchTask[0]);
                String query = s == null ? "" : s.toString().trim();
                searchTask[0] = () -> {
                    applyFilterAndSearch(context, query, currentFilter[0], results, dialog, detailHolder, density);
                };
                main.postDelayed(searchTask[0], 280);
            }
        };
        field.addTextChangedListener(watcher);

        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                if (searchTask[0] != null) main.removeCallbacks(searchTask[0]);
                String q = field.getText().toString().trim();
                applyFilterAndSearch(context, q, currentFilter[0], results, dialog, detailHolder, density);
                return true;
            }
            return false;
        });

        // Initial launch: populate automatically based on currentFilter
        applyFilterAndSearch(context, "", currentFilter[0], results, dialog, detailHolder, density);

        dialog.show();
        field.requestFocus();
    }

    private static TextView addFilterChip(LinearLayout bar, Context c, String label, float density) {
        TextView chip = new TextView(c);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setTypeface(Ui.displaySemibold);
        chip.setFocusable(true);
        chip.setClickable(true);

        int cPadH = Math.round(16 * density);
        int cPadV = Math.round(7 * density);
        chip.setPadding(cPadH, cPadV, cPadH, cPadV);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = Math.round(12 * density);
        bar.addView(chip, p);
        return chip;
    }

    private static void updateChipsVisuals(List<TextView> chips, List<Integer> filters, int activeFilter, float density) {
        for (int i = 0; i < chips.size(); i++) {
            TextView chip = chips.get(i);
            boolean isSelected = filters.get(i) == activeFilter;
            boolean hasFocus = chip.hasFocus();

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(14 * density);

            if (isSelected) {
                bg.setColor(0xFFFFFFFF);
                chip.setTextColor(0xFF101012);
            } else if (hasFocus) {
                bg.setColor(0x59FFFFFF);
                chip.setTextColor(0xFFFFFFFF);
            } else {
                bg.setColor(0x22FFFFFF);
                chip.setTextColor(0xD9FFFFFF);
            }
            chip.setBackground(bg);
        }
    }

    // MARK: - Detail Panel Builder

    private static DetailPanelHolder buildDetailPanel(Context c, float density, Dialog dialog) {
        DetailPanelHolder h = new DetailPanelHolder();

        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Math.round(24 * density), Math.round(20 * density), Math.round(24 * density), Math.round(24 * density));

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(0x18FFFFFF);
        panelBg.setCornerRadius(16 * density);
        panelBg.setStroke(Math.round(1 * density), 0x1FFFFFFF);
        panel.setBackground(panelBg);
        h.container = panel;

        // Backdrop / Hero Image (Cinematic widescreen)
        ImageView hero = new ImageView(c);
        hero.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable heroBg = new GradientDrawable();
        heroBg.setColor(0x26FFFFFF);
        heroBg.setCornerRadius(12 * density);
        hero.setBackground(heroBg);
        hero.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 12 * density);
            }
        });
        hero.setClipToOutline(true);

        int heroH = Math.round(160 * density);
        panel.addView(hero, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heroH));
        h.image = hero;

        // Title
        TextView title = new TextView(c);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Ui.displaySemibold);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, Math.round(14 * density), 0, Math.round(4 * density));
        panel.addView(title);
        h.title = title;

        // Meta Line (Kind · Year · IMDb badge)
        LinearLayout metaRow = new LinearLayout(c);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView meta = new TextView(c);
        meta.setTextColor(0x99FFFFFF);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        metaRow.addView(meta);
        h.meta = meta;

        // IMDb badge
        LinearLayout imdbBadge = new LinearLayout(c);
        imdbBadge.setOrientation(LinearLayout.HORIZONTAL);
        imdbBadge.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(0xFFF5C518);
        badgeBg.setCornerRadius(4 * density);
        imdbBadge.setBackground(badgeBg);
        imdbBadge.setPadding(Math.round(6 * density), Math.round(2 * density), Math.round(6 * density), Math.round(2 * density));

        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeParams.leftMargin = Math.round(12 * density);

        TextView imdbScore = new TextView(c);
        imdbScore.setTextColor(0xFF000000);
        imdbScore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        imdbScore.setTypeface(Ui.displaySemibold);
        imdbBadge.addView(imdbScore);

        metaRow.addView(imdbBadge, badgeParams);
        panel.addView(metaRow);
        h.imdbBadge = imdbBadge;
        h.imdbScore = imdbScore;

        // Synopsis / Overview inside ScrollView for full, complete text without truncation
        ScrollView overviewScroll = new ScrollView(c);
        overviewScroll.setVerticalScrollBarEnabled(false);
        overviewScroll.setFillViewport(true);

        TextView overview = new TextView(c);
        overview.setTextColor(0xCCFFFFFF);
        overview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        overview.setLineSpacing(0, 1.3f);
        overview.setPadding(0, Math.round(12 * density), 0, Math.round(8 * density));
        overviewScroll.addView(overview, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addView(overviewScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        h.overview = overview;

        // Action Launch Button
        TextView actionBtn = new TextView(c);
        actionBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        actionBtn.setTypeface(Ui.displaySemibold);
        actionBtn.setGravity(Gravity.CENTER);
        actionBtn.setFocusable(true);
        actionBtn.setClickable(true);

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFFFFFFFF);
        btnBg.setCornerRadius(10 * density);
        actionBtn.setBackground(btnBg);
        actionBtn.setTextColor(0xFF0D0D10);

        int aPadV = Math.round(10 * density);
        actionBtn.setPadding(0, aPadV, 0, aPadV);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.topMargin = Math.round(16 * density);
        panel.addView(actionBtn, btnParams);
        h.actionBtn = actionBtn;

        actionBtn.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                btnBg.setColor(0xFFF5C518); // Gold IMDb glow on focus
                actionBtn.setTextColor(0xFF000000);
            } else {
                btnBg.setColor(0xFFFFFFFF);
                actionBtn.setTextColor(0xFF0D0D10);
            }
        });

        actionBtn.setOnClickListener(v -> {
            if (h.currentResult != null) {
                dialog.dismiss();
                launchResult(c, h.currentResult);
            }
        });

        // Set idle state
        setIdleDetail(h, c);
        return h;
    }

    private static void setIdleDetail(DetailPanelHolder h, Context c) {
        h.currentResult = null;
        h.image.setImageDrawable(null);
        h.title.setText(Ui.tr("Search"));
        h.meta.setText(Ui.tr("Free My TV Universal Search"));
        h.imdbBadge.setVisibility(View.GONE);
        h.overview.setText(Ui.tr("Select any movie or series to see full details and launch directly."));
        h.actionBtn.setVisibility(View.GONE);
    }

    private static void showTmdbDetail(DetailPanelHolder h, SearchResult r, Context c) {
        h.currentResult = r;

        h.title.setText(r.title);
        String kindLabel = "tv".equals(r.mediaType) ? Ui.tr("Series") : Ui.tr("Movie");
        String year = r.date != null && r.date.length() >= 4 ? r.date.substring(0, 4) : "";
        h.meta.setText(year.isEmpty() ? kindLabel : kindLabel + " · " + year);

        if (r.voteAverage > 0) {
            h.imdbBadge.setVisibility(View.VISIBLE);
            h.imdbScore.setText("IMDb " + String.format(Locale.US, "%.1f", r.voteAverage));
        } else {
            h.imdbBadge.setVisibility(View.GONE);
        }

        h.overview.setText(r.overview != null && !r.overview.isEmpty() ? r.overview : Ui.tr("No overview available."));

        boolean nuvioInstalled = isInstalled(c, "com.nuvio.tv");
        h.actionBtn.setText("▶ " + (nuvioInstalled ? "Assistir no Nuvio" : Ui.tr("Watch")));
        h.actionBtn.setVisibility(View.VISIBLE);

        // Load backdrop (fallback to poster)
        String imgPath = !r.backdrop.isEmpty() ? r.backdrop : r.poster;
        h.image.setTag(imgPath);
        if (imgPath != null && !imgPath.isEmpty()) {
            Bitmap cached;
            synchronized (backdropCache) {
                cached = backdropCache.get(imgPath);
            }
            if (cached != null) {
                h.image.setImageBitmap(cached);
            } else {
                h.image.setImageDrawable(null);
                imageIo.execute(() -> {
                    String url = "https://image.tmdb.org/t/p/" + (!r.backdrop.isEmpty() ? "w780" : "w500") + imgPath;
                    Bitmap bmp = downloadBitmap(url);
                    if (bmp != null) {
                        synchronized (backdropCache) {
                            backdropCache.put(imgPath, bmp);
                        }
                        main.post(() -> {
                            if (imgPath.equals(h.image.getTag())) {
                                h.image.setImageBitmap(bmp);
                            }
                        });
                    }
                });
            }
        } else {
            h.image.setImageDrawable(null);
        }
    }

    // MARK: - Filter and Search Orchestrator

    private static void applyFilterAndSearch(Context c, String query, int filter, LinearLayout container, Dialog dialog, DetailPanelHolder detail, float density) {
        container.removeAllViews();
        String tmdbKey = Recs.key();

        if (query.isEmpty()) {
            // When query is empty, filter selects category
            switch (filter) {
                case FILTER_ALL:
                    loadTrending(c, container, dialog, detail, density);
                    break;
                case FILTER_MOVIES:
                    loadCategory(c, "/discover/movie?sort_by=popularity.desc", "movie", Ui.tr("Movies"), container, dialog, detail, density);
                    break;
                case FILTER_SHOWS:
                    loadCategory(c, "/discover/tv?sort_by=popularity.desc", "tv", Ui.tr("Shows"), container, dialog, detail, density);
                    break;
            }
            return;
        }

        if (tmdbKey.isEmpty()) return;

        String sectionTitle;
        if (filter == FILTER_MOVIES) {
            sectionTitle = Ui.tr("Movies");
        } else if (filter == FILTER_SHOWS) {
            sectionTitle = Ui.tr("Shows");
        } else {
            sectionTitle = Ui.tr("Movies & Shows");
        }

        TextView tmdbSection = makeSectionHeader(c, sectionTitle, density);
        container.addView(tmdbSection);

        TextView loading = makeLoadingView(c, density);
        container.addView(loading);

        searchIo.execute(() -> {
            List<SearchResult> results;
            if (filter == FILTER_MOVIES) {
                results = fetchTmdbSpecific("search/movie", query, tmdbKey, "movie");
            } else if (filter == FILTER_SHOWS) {
                results = fetchTmdbSpecific("search/tv", query, tmdbKey, "tv");
            } else {
                results = fetchTmdbResults(query, tmdbKey);
            }

            main.post(() -> {
                container.removeView(loading);
                if (results.isEmpty()) {
                    container.addView(makeEmptyView(c, Ui.tr("No titles found.")));
                    setIdleDetail(detail, c);
                    return;
                }
                populateResults(c, results, container, dialog, detail, density);
            });
        });
    }

    private static void loadTrending(Context c, LinearLayout container, Dialog dialog, DetailPanelHolder detail, float density) {
        String tmdbKey = Recs.key();
        if (tmdbKey.isEmpty()) return;

        TextView section = makeSectionHeader(c, Ui.tr("Trending"), density);
        container.addView(section);

        TextView loading = makeLoadingView(c, density);
        container.addView(loading);

        searchIo.execute(() -> {
            String lang = Ui.language.equals("pt") ? "pt-BR" : "en-US";
            String url = "https://api.tmdb.org/3/trending/all/week?language=" + lang + "&api_key=" + tmdbKey;
            List<SearchResult> results = fetchFromEndpoint(url, "movie");
            main.post(() -> {
                container.removeView(loading);
                populateResults(c, results, container, dialog, detail, density);
            });
        });
    }

    private static void loadCategory(Context c, String path, String defaultType, String title, LinearLayout container, Dialog dialog, DetailPanelHolder detail, float density) {
        String tmdbKey = Recs.key();
        if (tmdbKey.isEmpty()) return;

        TextView section = makeSectionHeader(c, title, density);
        container.addView(section);

        TextView loading = makeLoadingView(c, density);
        container.addView(loading);

        searchIo.execute(() -> {
            String lang = Ui.language.equals("pt") ? "pt-BR" : "en-US";
            String sep = path.contains("?") ? "&" : "?";
            String url = "https://api.tmdb.org/3" + path + sep + "language=" + lang + "&api_key=" + tmdbKey;
            List<SearchResult> results = fetchFromEndpoint(url, defaultType);
            main.post(() -> {
                container.removeView(loading);
                populateResults(c, results, container, dialog, detail, density);
            });
        });
    }

    private static void populateResults(Context c, List<SearchResult> results, LinearLayout container, Dialog dialog, DetailPanelHolder detail, float density) {
        if (results.isEmpty()) return;
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            View row = makeTmdbRow(c, r, dialog, detail, density);
            container.addView(row);
            if (i == 0) {
                showTmdbDetail(detail, r, c);
            }
        }
    }

    // MARK: - Row Builders (Left Results)

    private static View makeTmdbRow(Context c, SearchResult r, Dialog dialog, DetailPanelHolder detail, float density) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);

        int padV = Math.round(8 * density), padH = Math.round(14 * density);
        row.setPadding(padH, padV, padH, padV);

        GradientDrawable focusBg = new GradientDrawable();
        focusBg.setCornerRadius(12 * density);

        // Thumbnail Poster
        ImageView posterView = new ImageView(c);
        posterView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable posterBg = new GradientDrawable();
        posterBg.setColor(0x26FFFFFF);
        posterBg.setCornerRadius(8 * density);
        posterView.setBackground(posterBg);
        posterView.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 8 * density);
            }
        });
        posterView.setClipToOutline(true);

        int posterW = Math.round(44 * density);
        int posterH = Math.round(66 * density);
        LinearLayout.LayoutParams posterParams = new LinearLayout.LayoutParams(posterW, posterH);
        posterParams.rightMargin = Math.round(16 * density);
        row.addView(posterView, posterParams);

        // Load thumbnail async
        posterView.setTag(r.poster);
        if (r.poster != null && !r.poster.isEmpty()) {
            Bitmap cached;
            synchronized (posterCache) {
                cached = posterCache.get(r.poster);
            }
            if (cached != null) {
                posterView.setImageBitmap(cached);
            } else {
                final String path = r.poster;
                imageIo.execute(() -> {
                    String url = "https://image.tmdb.org/t/p/w185" + path;
                    Bitmap bmp = downloadBitmap(url);
                    if (bmp != null) {
                        synchronized (posterCache) {
                            posterCache.put(path, bmp);
                        }
                        main.post(() -> {
                            if (path.equals(posterView.getTag())) {
                                posterView.setImageBitmap(bmp);
                            }
                        });
                    }
                });
            }
        }

        // Info Column
        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(c);
        title.setText(r.title);
        title.setTextColor(0xF2FFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Ui.displaySemibold);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);

        LinearLayout metaRow = new LinearLayout(c);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(0, Math.round(3 * density), 0, 0);

        TextView meta = new TextView(c);
        String kindLabel = "tv".equals(r.mediaType) ? Ui.tr("Series") : Ui.tr("Movie");
        String year = r.date != null && r.date.length() >= 4 ? r.date.substring(0, 4) : "";
        meta.setText(year.isEmpty() ? kindLabel : kindLabel + " · " + year);
        meta.setTextColor(0x8CFFFFFF);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        metaRow.addView(meta);

        if (r.voteAverage > 0) {
            LinearLayout imdbBadge = new LinearLayout(c);
            imdbBadge.setOrientation(LinearLayout.HORIZONTAL);
            imdbBadge.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(0xFFF5C518);
            badgeBg.setCornerRadius(4 * density);
            badgeBg.setStroke(Math.round(1 * density), 0x26000000);
            imdbBadge.setBackground(badgeBg);
            imdbBadge.setPadding(Math.round(5 * density), Math.round(1 * density), Math.round(5 * density), Math.round(1 * density));

            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeParams.leftMargin = Math.round(8 * density);

            TextView imdbText = new TextView(c);
            imdbText.setText("IMDb " + String.format(Locale.US, "%.1f", r.voteAverage));
            imdbText.setTextColor(0xFF000000);
            imdbText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            imdbText.setTypeface(Ui.displaySemibold);
            imdbBadge.addView(imdbText);

            metaRow.addView(imdbBadge, badgeParams);
        }

        info.addView(metaRow);
        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                focusBg.setColor(0xFFFFFFFF);
                title.setTextColor(0xFF101012);
                meta.setTextColor(0xFF55555A);
                row.setBackground(focusBg);
                showTmdbDetail(detail, r, c);
            } else {
                row.setBackground(null);
                title.setTextColor(0xF2FFFFFF);
                meta.setTextColor(0x8CFFFFFF);
            }
        });

        row.setOnClickListener(v -> {
            dialog.dismiss();
            launchResult(c, r);
        });

        return row;
    }

    // MARK: - Utilities & Launchers

    private static TextView makeSectionHeader(Context c, String title, float density) {
        TextView tv = new TextView(c);
        tv.setText(title.toUpperCase(Ui.locale()));
        tv.setTextColor(0x80FFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Ui.displaySemibold);
        tv.setPadding(0, Math.round(14 * density), 0, Math.round(6 * density));
        return tv;
    }

    private static TextView makeLoadingView(Context c, float density) {
        TextView tv = new TextView(c);
        tv.setText(Ui.tr("Searching…"));
        tv.setTextColor(0x80FFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(0, Math.round(8 * density), 0, Math.round(12 * density));
        return tv;
    }

    private static TextView makeEmptyView(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(0x80FFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        return tv;
    }

    private static void launchResult(Context c, SearchResult r) {
        if (isInstalled(c, "com.nuvio.tv")) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setComponent(new ComponentName("com.nuvio.tv", "com.nuvio.tv.MainActivity"));
            intent.putExtra("contentId", "tmdb:" + r.id);
            intent.putExtra("contentType", r.mediaType);
            intent.putExtra("launchMode", "detail");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            c.startActivity(intent);
        } else {
            try {
                Intent searchIntent = new Intent(Intent.ACTION_SEARCH);
                searchIntent.setPackage("com.google.android.youtube.tv");
                searchIntent.putExtra("query", r.title);
                searchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(searchIntent);
            } catch (Exception ignored) {}
        }
    }

    private static boolean isInstalled(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Bitmap downloadBitmap(String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) return null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "FreeMyTV/1.0");
            if (conn.getResponseCode() == 200) {
                try (InputStream in = conn.getInputStream()) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    return BitmapFactory.decodeStream(in, null, opts);
                }
            }
            conn.disconnect();
        } catch (Exception ignored) {}
        return null;
    }

    private static List<SearchResult> fetchTmdbResults(String query, String apiKey) {
        try {
            String lang = Ui.language.equals("pt") ? "pt-BR" : "en-US";
            String q = URLEncoder.encode(query, "UTF-8");
            String url = "https://api.tmdb.org/3/search/multi?query=" + q + "&language=" + lang + "&include_adult=false&api_key=" + apiKey;
            return fetchFromEndpoint(url, "movie");
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static List<SearchResult> fetchTmdbSpecific(String endpoint, String query, String apiKey, String defaultType) {
        try {
            String lang = Ui.language.equals("pt") ? "pt-BR" : "en-US";
            String q = URLEncoder.encode(query, "UTF-8");
            String url = "https://api.tmdb.org/3/" + endpoint + "?query=" + q + "&language=" + lang + "&include_adult=false&api_key=" + apiKey;
            return fetchFromEndpoint(url, defaultType);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static List<SearchResult> fetchFromEndpoint(String urlStr, String defaultMediaType) {
        List<SearchResult> list = new ArrayList<>();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "FreeMyTV/1.0");

            if (conn.getResponseCode() == 200) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = conn.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                JSONObject root = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
                JSONArray results = root.optJSONArray("results");
                for (int i = 0; results != null && i < results.length() && list.size() < 25; i++) {
                    JSONObject o = results.getJSONObject(i);
                    String media = o.optString("media_type", defaultMediaType);
                    if (!"movie".equals(media) && !"tv".equals(media)) continue;

                    SearchResult sr = new SearchResult();
                    sr.id = o.optInt("id");
                    sr.mediaType = media;
                    sr.title = o.optString("movie".equals(media) ? "title" : "name");
                    if (sr.title.isEmpty()) continue;
                    sr.date = o.optString("movie".equals(media) ? "release_date" : "first_air_date");
                    sr.poster = o.optString("poster_path", "");
                    sr.backdrop = o.optString("backdrop_path", "");
                    if (sr.poster.isEmpty()) sr.poster = sr.backdrop;
                    sr.overview = o.optString("overview", "");
                    sr.voteAverage = o.optDouble("vote_average", 0.0);
                    sr.voteCount = o.optInt("vote_count", 0);
                    list.add(sr);
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            AppLog.w("tmdb fetch: " + e.getMessage());
        }
        return list;
    }

    private static final class DetailPanelHolder {
        View container;
        ImageView image;
        TextView title;
        TextView meta;
        LinearLayout imdbBadge;
        TextView imdbScore;
        TextView overview;
        TextView actionBtn;
        SearchResult currentResult;
    }

    private static final class SearchResult {
        int id;
        String mediaType;
        String title;
        String date;
        String poster;
        String backdrop;
        String overview;
        double voteAverage;
        int voteCount;
    }
}
