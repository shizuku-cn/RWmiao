package com.shizuku.rwmiao.module.playerinfo;

import android.content.Context;
import android.content.res.Configuration;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import com.shizuku.rwmiao.config.SettingsContract;
import com.shizuku.rwmiao.module.RWmiaoModule;

final class PlayerInfoOverlay extends FrameLayout {
    private static final String[] PAGE_TITLES = {"经济", "单位数"};
    private static final long PANEL_ANIMATION_MS = 180L;
    private static final long BALL_ANIMATION_MS = 140L;

    interface Callback {
        void onPanelExpandedChanged(boolean expanded);
        void onPageChanged(int page);
    }

    static final class PlayerRow {
        final int slot;
        final String name;
        final String information;
        final int numberColor;
        final int nameColor;

        PlayerRow(int slot, String name, String information,
                  int numberColor, int nameColor) {
            this.slot = slot;
            this.name = name;
            this.information = information;
            this.numberColor = numberColor;
            this.nameColor = nameColor;
        }
    }

    private final Callback callback;
    private ThemePalette palette;
    private final int touchSlop;
    private final int margin;
    private final int ballSize;
    private final ImageButton ball;
    private final LinearLayout panel;
    private LinearLayout rows;
    private TextView empty;
    private TextView title;
    private TextView page;
    private TextView collapse;
    private TextView previousPage;
    private TextView nextPage;
    private final int panelWidth;
    private boolean expanded;
    private boolean ballPositioned;
    private boolean panelPositioned;
    private int pageIndex;

    PlayerInfoOverlay(Context context, ThemePalette palette, Callback callback) {
        super(context);
        this.callback = callback;
        this.palette = palette == null ? ThemePalette.from(context, null) : palette;
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        margin = dp(10);
        ballSize = dp(36);
        panelWidth = Math.min(dp(320), Math.max(dp(220),
                context.getResources().getDisplayMetrics().widthPixels - dp(24)));
        panel = buildPanel(context);
        addView(panel, new FrameLayout.LayoutParams(
                panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT));

        ball = new ImageButton(context);
        ball.setContentDescription("玩家信息面板");
        ball.setPadding(dp(5), dp(5), dp(5), dp(5));
        ball.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        ball.setImageDrawable(new TagOneDrawable(palette.primary, palette.onPrimaryContainer));
        ball.setBackground(circleBackground(palette.primaryContainer));
        if (Build.VERSION.SDK_INT >= 21) ball.setElevation(dp(3));
        ball.setOnTouchListener(new DragTouchListener(ball, true));
        addView(ball, new FrameLayout.LayoutParams(ballSize, ballSize));
        updatePageButtons();
    }

    private LinearLayout buildPanel(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(7), dp(8), dp(8));
        root.setBackground(panelBackground());
        root.setClickable(true);
        root.setOnTouchListener((view, event) -> true);
        if (Build.VERSION.SDK_INT >= 21) root.setElevation(dp(8));
        root.setVisibility(GONE);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(dp(0), 0, dp(0), dp(4));
        header.setOnTouchListener(new DragTouchListener(root, false));

        collapse = text(context, "−", 22, palette.onSurface);
        collapse.setGravity(android.view.Gravity.CENTER);
        collapse.setContentDescription("缩小到悬浮球");
        collapse.setClickable(true);
        collapse.setBackgroundColor(Color.TRANSPARENT);
        collapse.setOnClickListener(view -> collapsePanel());
        header.addView(collapse, new LinearLayout.LayoutParams(dp(32), dp(34)));

        title = text(context, "经济", 16, palette.onSurface);
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(34), 1f));

        previousPage = text(context, "<", 20, palette.onSurface);
        previousPage.setGravity(android.view.Gravity.CENTER);
        previousPage.setContentDescription("上一页");
        previousPage.setClickable(true);
        previousPage.setBackgroundColor(Color.TRANSPARENT);
        previousPage.setOnClickListener(view -> setPage(pageIndex - 1, -1));
        header.addView(previousPage, new LinearLayout.LayoutParams(dp(28), dp(34)));

        page = text(context, pageIndicator(), 12, palette.primary);
        page.setGravity(android.view.Gravity.CENTER);
        header.addView(page, new LinearLayout.LayoutParams(dp(36), dp(34)));

        nextPage = text(context, ">", 20, palette.onSurface);
        nextPage.setGravity(android.view.Gravity.CENTER);
        nextPage.setContentDescription("下一页");
        nextPage.setClickable(true);
        nextPage.setBackgroundColor(Color.TRANSPARENT);
        nextPage.setOnClickListener(view -> setPage(pageIndex + 1, 1));
        header.addView(nextPage, new LinearLayout.LayoutParams(dp(28), dp(34)));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        ScrollView scroll = new MaxHeightScrollView(context,
                Math.round(context.getResources().getDisplayMetrics().heightPixels * 0.52f));
        scroll.setFillViewport(true);
        scroll.setOnTouchListener(new SwipeListener());
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(4), dp(2), dp(4), dp(2));
        rows.setBackground(roundBackground(palette.surfaceVariant, dp(14)));
        scroll.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        empty = text(context, "暂无玩家信息", 13, palette.onSurface);
        empty.setGravity(android.view.Gravity.CENTER);
        empty.setPadding(0, dp(12), 0, dp(12));
        root.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    void placeDefault() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (!ballPositioned) {
            ballPositioned = true;
            setPosition(ball, margin, margin + dp(24));
        }
        if (!panelPositioned) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
            params.leftMargin = Math.max(margin, (getWidth() - panelWidth) / 2);
            params.topMargin = margin;
            panel.setLayoutParams(params);
        }
    }

    boolean isPanelExpanded() {
        return expanded;
    }

    int getPageIndex() {
        return pageIndex;
    }

    private void setPage(int next, int direction) {
        if (next < 0 || next >= PAGE_TITLES.length || next == pageIndex) return;
        pageIndex = next;
        title.setText(pageTitle(pageIndex));
        page.setText(pageIndicator());
        updatePageButtons();
        animatePage(direction);
        callback.onPageChanged(pageIndex);
    }

    private String pageTitle(int index) {
        return PAGE_TITLES[Math.max(0, Math.min(index, PAGE_TITLES.length - 1))];
    }

    private String pageIndicator() {
        return (pageIndex + 1) + "/" + PAGE_TITLES.length;
    }

    private void updatePageButtons() {
        previousPage.setEnabled(pageIndex > 0);
        previousPage.setAlpha(pageIndex > 0 ? 1f : 0.35f);
        nextPage.setEnabled(pageIndex + 1 < PAGE_TITLES.length);
        nextPage.setAlpha(pageIndex + 1 < PAGE_TITLES.length ? 1f : 0.35f);
    }

    private void animatePage(int direction) {
        if (!expanded) return;
        rows.animate().cancel();
        rows.setAlpha(0.35f);
        rows.setTranslationX(direction * dp(10));
        rows.animate().alpha(1f).translationX(0f).setDuration(150L)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
    }

    void updatePalette(ThemePalette next) {
        if (next == null) return;
        palette = next;
        ball.setImageDrawable(new TagOneDrawable(palette.primary, palette.onPrimaryContainer));
        ball.setBackground(circleBackground(palette.primaryContainer));
        panel.setBackground(panelBackground());
        rows.setBackground(roundBackground(palette.surfaceVariant, dp(14)));
        title.setTextColor(palette.onSurface);
        page.setTextColor(palette.primary);
        collapse.setTextColor(palette.onSurface);
        previousPage.setTextColor(palette.onSurface);
        nextPage.setTextColor(palette.onSurface);
        empty.setTextColor(palette.onSurface);
        updatePageButtons();
        for (int index = 0; index < rows.getChildCount(); index++) {
            View child = rows.getChildAt(index);
            if (child instanceof ViewGroup && ((ViewGroup) child).getChildCount() >= 3) {
                ViewGroup row = (ViewGroup) child;
                if (row.getChildAt(0) instanceof LayeredTextView) {
                    LayeredTextView number = (LayeredTextView) row.getChildAt(0);
                    number.setOutlineColor(palette.surface);
                }
                if (row.getChildAt(1) instanceof LayeredTextView) {
                    LayeredTextView name = (LayeredTextView) row.getChildAt(1);
                    name.setOutlineColor(palette.surface);
                }
                row.getChildAt(2).setBackgroundColor(Color.TRANSPARENT);
                ((TextView) row.getChildAt(2)).setTextColor(palette.onSurface);
            } else if (child instanceof View) {
                child.setBackgroundColor(palette.outline);
            }
        }
    }

    void updatePlayers(List<PlayerRow> values) {
        if (LooperCompat.isMainThread()) {
            updatePlayersOnMain(values);
        } else {
            post(() -> updatePlayersOnMain(values));
        }
    }

    private void updatePlayersOnMain(List<PlayerRow> values) {
        if (!expanded || panel.getVisibility() != VISIBLE) return;
        rows.removeAllViews();
        if (values == null || values.isEmpty()) {
            empty.setVisibility(VISIBLE);
            return;
        }
        empty.setVisibility(GONE);
        for (int index = 0; index < values.size(); index++) {
            PlayerRow value = values.get(index);
            rows.addView(buildRow(value), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (index + 1 < values.size()) {
                View divider = new View(getContext());
                divider.setBackgroundColor(palette.outline);
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                dividerParams.leftMargin = dp(4);
                dividerParams.rightMargin = dp(4);
                rows.addView(divider, dividerParams);
            }
        }
    }

    private LinearLayout buildRow(PlayerRow value) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(5), dp(2), dp(5), dp(2));

        LayeredTextView number = layeredText(getContext(), String.valueOf(value.slot + 1),
                14, value.numberColor, palette.surface);
        number.setGravity(android.view.Gravity.CENTER_VERTICAL);
        number.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(number, new LinearLayout.LayoutParams(dp(32), dp(30)));

        LayeredTextView name = layeredText(getContext(), value.name, 14, value.nameColor,
                palette.surface);
        name.setGravity(android.view.Gravity.CENTER_VERTICAL);
        name.setMaxLines(1);
        name.setSingleLine(true);
        name.setHorizontallyScrolling(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setMaxWidth(dp(150));
        row.addView(name, new LinearLayout.LayoutParams(0, dp(30), 1f));

        TextView information = text(getContext(), value.information, 13, palette.onSurface);
        information.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.RIGHT);
        information.setSingleLine(true);
        information.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(information, new LinearLayout.LayoutParams(dp(112), dp(30)));
        return row;
    }

    void release() {
        expanded = false;
        panel.animate().setListener(null).cancel();
        ball.animate().setListener(null).cancel();
        ball.setOnTouchListener(null);
        panel.setOnTouchListener(null);
        callback.onPanelExpandedChanged(false);
    }

    private void expandPanel() {
        if (expanded) return;
        expanded = true;
        ball.animate().setListener(null).cancel();
        panel.animate().setListener(null).cancel();
        panel.setVisibility(VISIBLE);
        panel.setAlpha(0f);
        panel.setScaleX(0.92f);
        panel.setScaleY(0.92f);
        panel.bringToFront();
        requestLayout();
        panel.post(() -> {
            if (!expanded) return;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
            if (!panelPositioned) {
                panelPositioned = true;
                setPosition(panel, (getWidth() - panel.getMeasuredWidth()) / 2,
                        (getHeight() - panel.getMeasuredHeight()) / 2);
            } else {
                setPosition(panel, params.leftMargin, params.topMargin);
            }
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(PANEL_ANIMATION_MS)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .start();
        });
        ball.setVisibility(VISIBLE);
        ball.setAlpha(1f);
        ball.setScaleX(1f);
        ball.setScaleY(1f);
        ball.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f)
                .setDuration(BALL_ANIMATION_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (expanded) ball.setVisibility(GONE);
                    }
                }).start();
        callback.onPanelExpandedChanged(true);
    }

    private void collapsePanel() {
        if (!expanded) return;
        expanded = false;
        panel.animate().setListener(null).cancel();
        ball.animate().setListener(null).cancel();
        ball.setVisibility(VISIBLE);
        ball.setAlpha(0f);
        ball.setScaleX(0.85f);
        ball.setScaleY(0.85f);
        ball.bringToFront();
        ball.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(BALL_ANIMATION_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
        panel.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f)
                .setDuration(PANEL_ANIMATION_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (!expanded) panel.setVisibility(GONE);
                    }
                }).start();
        callback.onPanelExpandedChanged(false);
    }

    private void setPosition(View view, int left, int top) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        params.leftMargin = clamp(left, margin, Math.max(margin, getWidth() - view.getMeasuredWidth() - margin));
        params.topMargin = clamp(top, margin, Math.max(margin, getHeight() - view.getMeasuredHeight() - margin));
        view.setLayoutParams(params);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private TextView text(Context context, String value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    private LayeredTextView layeredText(Context context, String value, float size,
                                        int color, int outlineColor) {
        LayeredTextView view = new LayeredTextView(context, outlineColor, dp(1));
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable panelBackground() {
        GradientDrawable drawable = roundBackground(palette.surface, dp(22));
        drawable.setStroke(dp(1), palette.outline);
        return drawable;
    }

    private GradientDrawable roundBackground(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable circleBackground(int color) {
        return roundBackground(color, ballSize / 2);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class DragTouchListener implements OnTouchListener {
        private final View target;
        private final boolean opensPanel;
        private float downX;
        private float downY;
        private int startLeft;
        private int startTop;
        private boolean moved;

        DragTouchListener(View target, boolean opensPanel) {
            this.target = target;
            this.opensPanel = opensPanel;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) target.getLayoutParams();
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startLeft = params.leftMargin;
                    startTop = params.topMargin;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!moved && Math.hypot(dx, dy) >= touchSlop) moved = true;
                    if (moved) {
                        if (target == panel) {
                            panelPositioned = true;
                        } else if (target == ball) {
                            ballPositioned = true;
                        }
                        setPosition(target, Math.round(startLeft + dx), Math.round(startTop + dy));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && opensPanel) expandPanel();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                default:
                    return true;
            }
        }
    }

    private final class SwipeListener implements OnTouchListener {
        private float downX;
        private float downY;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    return false;
                case MotionEvent.ACTION_UP:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    int threshold = dp(48);
                    if (Math.abs(dx) >= threshold && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                        setPage(pageIndex + (dx < 0 ? 1 : -1), dx < 0 ? 1 : -1);
                    }
                    return false;
                case MotionEvent.ACTION_CANCEL:
                default:
                    return false;
            }
        }
    }

    private static final class MaxHeightScrollView extends ScrollView {
        private final int maxHeight;

        MaxHeightScrollView(Context context, int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            setMeasuredDimension(getMeasuredWidth(), Math.min(getMeasuredHeight(), maxHeight));
        }
    }

    private static final class LayeredTextView extends TextView {
        private int outlineColor;
        private final float outlineWidth;

        LayeredTextView(Context context, int outlineColor, float outlineWidth) {
            super(context);
            this.outlineColor = outlineColor;
            this.outlineWidth = outlineWidth;
        }

        void setOutlineColor(int color) {
            outlineColor = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Paint paint = getPaint();
            int originalColor = paint.getColor();
            Paint.Style originalStyle = paint.getStyle();
            float originalStrokeWidth = paint.getStrokeWidth();
            Paint.Join originalJoin = paint.getStrokeJoin();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(outlineWidth);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(outlineColor);
            super.onDraw(canvas);
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(originalStrokeWidth);
            paint.setColor(originalColor);
            paint.setStrokeJoin(originalJoin);
            super.onDraw(canvas);
            paint.setStyle(originalStyle);
            paint.setStrokeWidth(originalStrokeWidth);
            paint.setColor(originalColor);
            paint.setStrokeJoin(originalJoin);
        }
    }

    private static final class LooperCompat {
        static boolean isMainThread() {
            return android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
        }
    }

    static final class ThemePalette {
        final int primary;
        final int onPrimary;
        final int primaryContainer;
        final int onPrimaryContainer;
        final int surface;
        final int surfaceVariant;
        final int onSurface;
        final int outline;

        ThemePalette(int primary, int onPrimary, int primaryContainer,
                     int onPrimaryContainer, int surface, int surfaceVariant,
                     int onSurface, int outline) {
            this.primary = primary;
            this.onPrimary = onPrimary;
            this.primaryContainer = primaryContainer;
            this.onPrimaryContainer = onPrimaryContainer;
            this.surface = surface;
            this.surfaceVariant = surfaceVariant;
            this.onSurface = onSurface;
            this.outline = outline;
        }

        static ThemePalette from(Context context, RWmiaoModule host) {
            boolean dark = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            int colorMode = SettingsContract.UI_COLOR_DEFAULT;
            int themeMode = SettingsContract.UI_THEME_SYSTEM;
            if (host != null) {
                colorMode = host.uiColorMode();
                themeMode = host.uiThemeMode();
            }
            if (themeMode == SettingsContract.UI_THEME_LIGHT) dark = false;
            if (themeMode == SettingsContract.UI_THEME_DARK) dark = true;

            switch (colorMode) {
                case SettingsContract.UI_COLOR_DYNAMIC:
                    ThemePalette dynamic = dynamic(context, dark);
                    if (dynamic != null) return dynamic;
                    return defaults(dark);
                case SettingsContract.UI_COLOR_GREEN:
                    return green(dark);
                case SettingsContract.UI_COLOR_BLUE:
                    return blue(dark);
                case SettingsContract.UI_COLOR_PINK:
                    return pink(dark);
                case SettingsContract.UI_COLOR_YELLOW:
                    return yellow(dark);
                case SettingsContract.UI_COLOR_ORANGE:
                    return orange(dark);
                case SettingsContract.UI_COLOR_RED:
                    return red(dark);
                case SettingsContract.UI_COLOR_CYAN:
                    return cyan(dark);
                default:
                    return defaults(dark);
            }
        }

        private static ThemePalette defaults(boolean dark) {
            return dark
                    ? new ThemePalette(0xFFD0BCFF, 0xFF381E72, 0xFF4F378B,
                    0xFFEADDFF, 0xFF1C1B1F, 0xFF49454F,
                    0xFFE6E1E5, 0xFF938F99)
                    : new ThemePalette(0xFF6750A4, Color.WHITE, 0xFFEADDFF,
                    0xFF21005D, 0xFFFFFBFE, 0xFFE7E0EC,
                    0xFF1C1B1F, 0xFF79747E);
        }

        private static ThemePalette green(boolean dark) {
            return dark
                    ? new ThemePalette(0xFF6DDF73, 0xFF00390A, 0xFF005313,
                    0xFF8BFA8D, 0xFF101F0E, 0xFF3A4A37,
                    0xFFE3F3DC, 0xFF8F938A)
                    : new ThemePalette(0xFF006E1C, Color.WHITE, 0xFF8BFA8D,
                    0xFF002204, 0xFFF8FBF3, 0xFFD5E8CF,
                    0xFF191D18, 0xFF737970);
        }

        private static ThemePalette blue(boolean dark) {
            return dark
                    ? new ThemePalette(0xFFAAC7FF, 0xFF0A305F, 0xFF284777,
                    0xFFD6E3FF, 0xFF111318, 0xFF3F4759,
                    0xFFE2E2E9, 0xFF8D9199)
                    : new ThemePalette(0xFF415F91, Color.WHITE, 0xFFD6E3FF,
                    0xFF001A41, 0xFFF9F9FF, 0xFFDBE2F9,
                    0xFF1A1B20, 0xFF74777F);
        }

        private static ThemePalette pink(boolean dark) {
            return dark
                    ? new ThemePalette(0xFFFFB0D0, 0xFF650033, 0xFF7D294F,
                    0xFFFFD9E6, 0xFF201A1D, 0xFF603B4B,
                    0xFFF0DEE4, 0xFF9A8C91)
                    : new ThemePalette(0xFFC2185B, Color.WHITE, 0xFFFFD9E6,
                    0xFF3E001D, 0xFFFFF8FA, 0xFFFFD9E6,
                    0xFF201A1D, 0xFF857377);
        }

        private static ThemePalette yellow(boolean dark) {
            return dark
                    ? new ThemePalette(0xFFDBC66E, 0xFF3A3200, 0xFF514700,
                    0xFFF8E287, 0xFF1D1C16, 0xFF4C472D,
                    0xFFE8E2CA, 0xFF97927D)
                    : new ThemePalette(0xFF6D5F00, Color.WHITE, 0xFFF8E287,
                    0xFF211B00, 0xFFFFFBF1, 0xFFECE5C4,
                    0xFF1E1B0F, 0xFF7A7661);
        }

        private static ThemePalette orange(boolean dark) {
            return dark
                    ? new ThemePalette(0xFFFFB596, 0xFF5F1A00, 0xFF7E2C08,
                    0xFFFFDBCA, 0xFF201A17, 0xFF683C20,
                    0xFFF4DED5, 0xFFA08D84)
                    : new ThemePalette(0xFFA63C00, Color.WHITE, 0xFFFFDBCA,
                    0xFF3A1300, 0xFFFFF8F5, 0xFFFFDBCA,
                    0xFF201A17, 0xFF85746B);
        }

        private static ThemePalette red(boolean dark) {
            return dark
                    ? new ThemePalette(0xFFFFB4AB, 0xFF690005, 0xFF8C1D18,
                    0xFFFFDAD6, 0xFF201A19, 0xFF5D3F3C,
                    0xFFF4DDDA, 0xFFA08C89)
                    : new ThemePalette(0xFFBA1A1A, Color.WHITE, 0xFFFFDAD6,
                    0xFF410002, 0xFFFFF8F7, 0xFFFFDAD6,
                    0xFF201A19, 0xFF857370);
        }

        private static ThemePalette cyan(boolean dark) {
            return dark
                    ? new ThemePalette(0xFF4FD8EB, 0xFF00363D, 0xFF004F58,
                    0xFF97F0FF, 0xFF101F22, 0xFF334B4F,
                    0xFFDEE3E5, 0xFF899397)
                    : new ThemePalette(0xFF006874, Color.WHITE, 0xFF97F0FF,
                    0xFF00363D, 0xFFF5FAFC, 0xFF97F0FF,
                    0xFF171D1F, 0xFF70797D);
        }

        private static ThemePalette dynamic(Context context, boolean dark) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
            String primaryName = dark ? "system_accent1_200" : "system_accent1_600";
            String onPrimaryName = dark ? "system_accent1_800" : "system_accent1_0";
            String primaryContainerName = dark ? "system_accent1_700" : "system_accent1_100";
            String onPrimaryContainerName = dark ? "system_accent1_100" : "system_accent1_900";
            int primary = systemColor(context, primaryName);
            int onPrimary = systemColor(context, onPrimaryName);
            int primaryContainer = systemColor(context, primaryContainerName);
            int onPrimaryContainer = systemColor(context, onPrimaryContainerName);
            if (primary == Integer.MIN_VALUE || onPrimary == Integer.MIN_VALUE
                    || primaryContainer == Integer.MIN_VALUE
                    || onPrimaryContainer == Integer.MIN_VALUE) return null;
            ThemePalette base = defaults(dark);
            return new ThemePalette(primary, onPrimary, primaryContainer,
                    onPrimaryContainer, base.surface, base.surfaceVariant,
                    base.onSurface, base.outline);
        }

        private static int systemColor(Context context, String name) {
            int id = context.getResources().getIdentifier(name, "color", "android");
            if (id == 0) return Integer.MIN_VALUE;
            try {
                return context.getResources().getColor(id, context.getTheme());
            } catch (Throwable ignored) {
                return Integer.MIN_VALUE;
            }
        }
    }

    private static final class TagOneDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path body = new Path();
        private final Path dot = new Path();
        private final int fillColor;
        private final int detailColor;

        TagOneDrawable(int fillColor, int detailColor) {
            this.fillColor = fillColor;
            this.detailColor = detailColor;
            body.moveTo(42.1691f, 29.2451f);
            body.lineTo(29.2631f, 42.1511f);
            body.cubicTo(28.5879f, 42.8271f, 27.6716f, 43.2069f,
                    26.7161f, 43.2069f);
            body.cubicTo(25.7606f, 43.2069f, 24.8444f, 42.8271f,
                    24.1691f, 42.1511f);
            body.lineTo(8f, 26f);
            body.lineTo(8f, 8f);
            body.lineTo(26f, 8f);
            body.lineTo(42.1691f, 24.169f);
            body.cubicTo(43.5649f, 25.5732f, 43.5649f, 27.841f,
                    42.1691f, 29.2451f);
            body.close();
            dot.addCircle(18.5f, 18.5f, 2.5f, Path.Direction.CW);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) return;
            int save = canvas.save();
            float scale = Math.min(bounds.width(), bounds.height()) / 48f;
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(scale, scale);

            paint.setColor(fillColor);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(body, paint);
            paint.setColor(detailColor);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(body, paint);
            paint.setColor(detailColor);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(dot, paint);
            canvas.restoreToCount(save);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
