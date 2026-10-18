package com.termux.app.setup;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ViewFlipper;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxInstaller;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Material You first run setup: welcome, terminal colors, font, shell extras and then the bootstrap
 * installation with a progress screen. Can also be opened again from the drawer to change the
 * appearance, in which case the welcome step is skipped and nothing is installed.
 */
public class SetupActivity extends AppCompatActivity {

    /** Set on the result intent when the appearance was changed, so that TermuxActivity reloads it. */
    public static final String EXTRA_APPEARANCE_CHANGED = "appearance_changed";

    private static final int STEP_WELCOME = 0;
    private static final int STEP_COLORS = 1;
    private static final int STEP_FONT = 2;
    private static final int STEP_SHELL = 3;
    private static final int STEP_INSTALL = 4;

    private static final int REQUEST_STORAGE_PERMISSION = 1200;

    private static final float FONT_SIZE_MIN_DP = 8;
    private static final float FONT_SIZE_MAX_DP = 20;

    /** The terminal draws characters one by one, so the previews must not show font ligatures either. */
    private static final String NO_LIGATURES = "\"liga\" 0, \"calt\" 0";

    private static final String LOG_TAG = "SetupActivity";

    private ViewFlipper mFlipper;
    private LinearProgressIndicator mProgress;
    private TextView mStepCounter;
    private MaterialButton mBackButton;
    private MaterialButton mNextButton;

    private final List<MaterialCardView> mSchemeCards = new ArrayList<>();
    private final List<TerminalAppearance.ColorScheme> mSchemes = new ArrayList<>();
    private TextView mFontPreview;
    private MaterialCardView mFontPreviewCard;
    private TextView mFontSizeValue;
    private TextView mShellPreview;
    private MaterialCardView mShellPreviewCard;
    private MaterialSwitch mPromptSwitch;
    private MaterialSwitch mGreetingSwitch;
    private MaterialSwitch mStorageSwitch;

    private TerminalAppearance.Choices mChoices;
    private Typeface mPreviewTypeface;

    /** If the bootstrap still has to be installed, i.e. this is the first run. */
    private boolean mIsFirstRun;
    private int mFirstStep;
    private int mStep;
    private boolean mIsInstalling;
    private boolean mIsDone;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        setupEdgeToEdge();

        mIsFirstRun = !TerminalAppearance.isBootstrapInstalled();
        mFirstStep = mIsFirstRun ? STEP_WELCOME : STEP_COLORS;

        mChoices = TerminalAppearance.loadChoices(this);
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(this);
        mChoices.fontSizePx = preferences != null ? preferences.getFontSize() : dpToPx(12);
        mPreviewTypeface = TerminalAppearance.getTypeface(this, mChoices.fontId);

        mFlipper = findViewById(R.id.setup_flipper);
        mProgress = findViewById(R.id.setup_progress);
        mStepCounter = findViewById(R.id.setup_step_counter);
        mBackButton = findViewById(R.id.setup_back);
        mNextButton = findViewById(R.id.setup_next);

        TextView logo = findViewById(R.id.setup_logo);
        logo.setTypeface(TerminalAppearance.getTypeface(this, TerminalAppearance.FONT_JETBRAINS_MONO), Typeface.BOLD);

        setupColorsStep();
        setupFontStep();
        setupShellStep();

        mBackButton.setOnClickListener(v -> goToStep(mStep - 1));
        mNextButton.setOnClickListener(v -> onNextClicked());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mIsInstalling) return; // Never interrupt the installation.
                if (!mIsDone && mStep > mFirstStep) {
                    goToStep(mStep - 1);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        mFlipper.setInAnimation(null);
        mFlipper.setOutAnimation(null);
        mFlipper.setDisplayedChild(mFirstStep);
        mStep = mFirstStep;
        updateNavigation();
    }

    private void setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        boolean isNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(!isNight);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(!isNight);

        View root = findViewById(R.id.setup_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }



    private void onNextClicked() {
        if (mIsDone) {
            openTerminal();
        } else if (mStep == STEP_SHELL) {
            goToStep(STEP_INSTALL);
            startInstall();
        } else {
            goToStep(mStep + 1);
        }
    }

    private void goToStep(int step) {
        if (step < mFirstStep || step > STEP_INSTALL || step == mStep) return;

        boolean forward = step > mStep;
        mFlipper.setInAnimation(this, forward ? R.anim.setup_enter_forward : R.anim.setup_enter_back);
        mFlipper.setOutAnimation(this, forward ? R.anim.setup_exit_forward : R.anim.setup_exit_back);
        mFlipper.setDisplayedChild(step);
        mStep = step;
        updateNavigation();
    }

    private void updateNavigation() {
        int stepCount = STEP_INSTALL - mFirstStep + 1;
        int stepNumber = mStep - mFirstStep + 1;
        mStepCounter.setText(getString(R.string.setup_step_counter, stepNumber, stepCount));
        mProgress.setProgressCompat(mIsDone ? 100 : Math.round(100f * stepNumber / stepCount), true);

        mBackButton.setVisibility(mStep > mFirstStep && mStep != STEP_INSTALL ? View.VISIBLE : View.INVISIBLE);

        if (mStep == STEP_INSTALL) {
            mNextButton.setText(R.string.setup_open_terminal);
            mNextButton.setVisibility(mIsDone ? View.VISIBLE : View.INVISIBLE);
        } else if (mStep == STEP_SHELL) {
            mNextButton.setText(mIsFirstRun ? R.string.setup_install : R.string.setup_apply);
            mNextButton.setVisibility(View.VISIBLE);
        } else {
            mNextButton.setText(mStep == STEP_WELCOME ? R.string.setup_start : R.string.setup_next);
            mNextButton.setVisibility(View.VISIBLE);
        }
    }



    private void setupColorsStep() {
        LinearLayout list = findViewById(R.id.setup_scheme_list);
        LayoutInflater inflater = LayoutInflater.from(this);

        mSchemes.addAll(TerminalAppearance.getColorSchemes(this));
        for (TerminalAppearance.ColorScheme scheme : mSchemes) {
            MaterialCardView card = (MaterialCardView) inflater.inflate(R.layout.item_setup_scheme, list, false);
            ((TextView) card.findViewById(R.id.scheme_name)).setText(scheme.nameRes);
            ((TextView) card.findViewById(R.id.scheme_description)).setText(scheme.descriptionRes);

            View preview = card.findViewById(R.id.scheme_preview);
            preview.setBackground(roundedRect(scheme.background, dpToPx(16)));

            TextView previewText = card.findViewById(R.id.scheme_preview_text);
            previewText.setTypeface(TerminalAppearance.getTypeface(this, TerminalAppearance.FONT_JETBRAINS_MONO));
            previewText.setFontFeatureSettings(NO_LIGATURES);
            previewText.setText(buildSchemeSample(scheme));

            LinearLayout swatches = card.findViewById(R.id.scheme_swatches);
            int[] swatchColors = {scheme.ansi[1], scheme.ansi[2], scheme.ansi[3], scheme.ansi[4],
                scheme.ansi[5], scheme.ansi[6], scheme.cursor, scheme.foreground};
            for (int color : swatchColors) {
                View swatch = new View(this);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dpToPx(16), dpToPx(16));
                params.setMarginEnd(dpToPx(6));
                swatch.setLayoutParams(params);
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(color);
                swatch.setBackground(circle);
                swatches.addView(swatch);
            }

            if (scheme.available) {
                card.setOnClickListener(v -> selectColorScheme(scheme.id));
            } else {
                card.setEnabled(false);
                card.setAlpha(0.5f);
            }

            list.addView(card);
            mSchemeCards.add(card);
        }

        // Fall back to an available scheme if the saved one is not.
        mChoices.colorSchemeId = TerminalAppearance.getColorScheme(this, mChoices.colorSchemeId).id;
        selectColorScheme(mChoices.colorSchemeId);
    }

    private void selectColorScheme(@NonNull String schemeId) {
        mChoices.colorSchemeId = schemeId;

        int primary = MaterialColors.getColor(mFlipper, androidx.appcompat.R.attr.colorPrimary);
        int outline = MaterialColors.getColor(mFlipper, com.google.android.material.R.attr.colorOutlineVariant);
        for (int i = 0; i < mSchemeCards.size(); i++) {
            MaterialCardView card = mSchemeCards.get(i);
            boolean checked = mSchemes.get(i).id.equals(schemeId);
            card.setChecked(checked);
            card.setStrokeColor(checked ? primary : outline);
            card.setStrokeWidth(dpToPx(checked ? 2 : 1));
        }

        updateFontPreview();
        updateShellPreview();
    }

    @NonNull
    private TerminalAppearance.ColorScheme getSelectedScheme() {
        for (TerminalAppearance.ColorScheme scheme : mSchemes) {
            if (scheme.id.equals(mChoices.colorSchemeId)) return scheme;
        }
        return TerminalAppearance.getColorScheme(this, mChoices.colorSchemeId);
    }



    private void setupFontStep() {
        mFontPreviewCard = findViewById(R.id.font_preview_card);
        mFontPreview = findViewById(R.id.font_preview);
        mFontPreview.setFontFeatureSettings(NO_LIGATURES);
        mFontSizeValue = findViewById(R.id.font_size_value);

        MaterialButtonToggleGroup toggle = findViewById(R.id.font_toggle);
        toggle.check(TerminalAppearance.FONT_SYSTEM.equals(mChoices.fontId) ? R.id.font_system : R.id.font_jetbrains_mono);
        ((MaterialButton) findViewById(R.id.font_jetbrains_mono)).setTypeface(
            TerminalAppearance.getTypeface(this, TerminalAppearance.FONT_JETBRAINS_MONO));
        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            mChoices.fontId = checkedId == R.id.font_system ? TerminalAppearance.FONT_SYSTEM : TerminalAppearance.FONT_JETBRAINS_MONO;
            mPreviewTypeface = TerminalAppearance.getTypeface(this, mChoices.fontId);
            updateFontPreview();
            updateShellPreview();
        });

        Slider slider = findViewById(R.id.font_size_slider);
        float sizeDp = Math.round(mChoices.fontSizePx / getResources().getDisplayMetrics().density);
        slider.setValue(Math.max(FONT_SIZE_MIN_DP, Math.min(FONT_SIZE_MAX_DP, sizeDp)));
        slider.setLabelFormatter(value -> String.valueOf(Math.round(value)));
        slider.addOnChangeListener((s, value, fromUser) -> {
            mChoices.fontSizePx = dpToPx(value);
            updateFontPreview();
        });
        mChoices.fontSizePx = dpToPx(slider.getValue());

        // The colors step was set up first, when this preview did not exist yet.
        updateFontPreview();
    }

    private void updateFontPreview() {
        if (mFontPreview == null) return;
        TerminalAppearance.ColorScheme scheme = getSelectedScheme();
        mFontPreviewCard.setCardBackgroundColor(scheme.background);
        mFontPreview.setTypeface(mPreviewTypeface);
        mFontPreview.setTextSize(TypedValue.COMPLEX_UNIT_PX, mChoices.fontSizePx);
        mFontPreview.setText(buildFontSample(scheme));
        mFontSizeValue.setText(String.valueOf(Math.round(mChoices.fontSizePx / getResources().getDisplayMetrics().density)));
    }



    private void setupShellStep() {
        mShellPreviewCard = findViewById(R.id.shell_preview_card);
        mShellPreview = findViewById(R.id.shell_preview);
        mShellPreview.setFontFeatureSettings(NO_LIGATURES);
        mPromptSwitch = findViewById(R.id.shell_prompt_switch);
        mGreetingSwitch = findViewById(R.id.shell_greeting_switch);
        mStorageSwitch = findViewById(R.id.shell_storage_switch);

        mPromptSwitch.setChecked(mChoices.prompt);
        mGreetingSwitch.setChecked(mChoices.greeting);
        mStorageSwitch.setChecked(PermissionUtils.checkStoragePermission(this, PermissionUtils.isLegacyExternalStoragePossible(this)));

        mPromptSwitch.setOnCheckedChangeListener((button, checked) -> {
            mChoices.prompt = checked;
            updateShellPreview();
        });
        mGreetingSwitch.setOnCheckedChangeListener((button, checked) -> {
            mChoices.greeting = checked;
            updateShellPreview();
        });
        mStorageSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(this, REQUEST_STORAGE_PERMISSION, false)) {
                // Stays checked while the permission dialog is shown, updated in onStoragePermissionResult().
                Logger.logDebug(LOG_TAG, "Requested storage permission");
            }
        });

        findViewById(R.id.shell_prompt_row).setOnClickListener(v -> mPromptSwitch.toggle());
        findViewById(R.id.shell_greeting_row).setOnClickListener(v -> mGreetingSwitch.toggle());
        findViewById(R.id.shell_storage_row).setOnClickListener(v -> mStorageSwitch.toggle());

        // Storage links are only set up once on the first run, afterwards termux-setup-storage does it.
        if (!mIsFirstRun) {
            findViewById(R.id.shell_storage_row).setVisibility(View.GONE);
            findViewById(R.id.shell_storage_divider).setVisibility(View.GONE);
        }

        updateShellPreview();
    }

    private void updateShellPreview() {
        if (mShellPreview == null) return;
        TerminalAppearance.ColorScheme scheme = getSelectedScheme();
        mShellPreviewCard.setCardBackgroundColor(scheme.background);
        mShellPreview.setTypeface(mPreviewTypeface);
        mShellPreview.setText(buildShellSample(scheme));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) onStoragePermissionResult();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_STORAGE_PERMISSION) onStoragePermissionResult();
    }

    private void onStoragePermissionResult() {
        mStorageSwitch.setChecked(PermissionUtils.checkStoragePermission(this, PermissionUtils.isLegacyExternalStoragePossible(this)));
    }



    private void startInstall() {
        mIsInstalling = true;

        TextView title = findViewById(R.id.install_title);
        TextView body = findViewById(R.id.install_body);
        TextView status = findViewById(R.id.install_status);
        title.setText(mIsFirstRun ? R.string.setup_installing_title : R.string.setup_applying_title);
        body.setText(mIsFirstRun ? R.string.setup_installing_body : R.string.setup_applying_body);
        status.setText(mIsFirstRun ? R.string.setup_status_unpacking : R.string.setup_status_applying);

        // Runs whenDone immediately if the bootstrap is already installed.
        TermuxInstaller.setupBootstrapIfNeeded(this, this::applyAppearance, false);
    }

    private void applyAppearance() {
        if (isFinishing() || isDestroyed()) return;
        ((TextView) findViewById(R.id.install_status)).setText(R.string.setup_status_applying);

        final TerminalAppearance.Choices choices = mChoices;
        final boolean setupStorage = mIsFirstRun && mStorageSwitch.isChecked();
        new Thread(() -> {
            String error = null;
            try {
                TerminalAppearance.apply(this, choices);
                if (setupStorage) TermuxInstaller.setupStorageSymlinks(this);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to apply the appearance", e);
                error = e.getMessage();
            }

            final String errorMessage = error;
            runOnUiThread(() -> onSetupFinished(errorMessage));
        }).start();
    }

    private void onSetupFinished(@Nullable String errorMessage) {
        if (isFinishing() || isDestroyed()) return;
        mIsInstalling = false;
        mIsDone = true;
        TerminalAppearance.setSetupCompleted(this);

        CircularProgressIndicator progress = findViewById(R.id.install_progress);
        progress.hide();

        ImageView doneIcon = findViewById(R.id.install_done_icon);
        doneIcon.setScaleX(0f);
        doneIcon.setScaleY(0f);
        doneIcon.setVisibility(View.VISIBLE);
        doneIcon.animate().scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(new OvershootInterpolator(2f)).start();

        ((TextView) findViewById(R.id.install_title)).setText(R.string.setup_done_title);
        ((TextView) findViewById(R.id.install_body)).setText(R.string.setup_done_body);
        ((TextView) findViewById(R.id.install_status)).setText(
            errorMessage == null ? "" : getString(R.string.setup_apply_failed, errorMessage));

        updateNavigation();
    }

    private void openTerminal() {
        if (mIsFirstRun) {
            startActivity(new Intent(this, TermuxActivity.class));
        } else {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_APPEARANCE_CHANGED, true));
        }
        finish();
    }



    /** Mini terminal used on the color scheme cards. */
    @NonNull
    private CharSequence buildSchemeSample(@NonNull TerminalAppearance.ColorScheme scheme) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        append(text, "~/projects", scheme.ansi[4], true);
        append(text, " $ ", scheme.foreground, false);
        append(text, "ls\n", scheme.foreground, false);
        append(text, "app", scheme.ansi[4], true);
        append(text, "  docs", scheme.ansi[4], true);
        append(text, "  run.sh", scheme.ansi[2], true);
        append(text, "  notes.md", scheme.foreground, false);
        return text;
    }

    /** Terminal session sample for the font step. */
    @NonNull
    private CharSequence buildFontSample(@NonNull TerminalAppearance.ColorScheme scheme) {
        int dim = ColorUtils.blendARGB(scheme.foreground, scheme.background, 0.45f);
        SpannableStringBuilder text = new SpannableStringBuilder();
        appendPrompt(text, scheme, "~/termux");
        append(text, "python3 --version\n", scheme.foreground, false);
        append(text, "Python 3.13.2\n", scheme.foreground, false);
        appendPrompt(text, scheme, "~/termux");
        append(text, "git status\n", scheme.foreground, false);
        append(text, "On branch ", scheme.foreground, false);
        append(text, "main\n", scheme.ansi[2], true);
        append(text, "modified: ", scheme.ansi[1], false);
        append(text, "app.py\n", scheme.ansi[1], false);
        append(text, "0O 1lI {} [] <> => != ~", dim, false);
        return text;
    }

    /** Greeting and prompt sample for the shell step. */
    @NonNull
    private CharSequence buildShellSample(@NonNull TerminalAppearance.ColorScheme scheme) {
        int dim = ColorUtils.blendARGB(scheme.foreground, scheme.background, 0.45f);
        SpannableStringBuilder text = new SpannableStringBuilder();
        if (mChoices.greeting) {
            append(text, "\u2580\u2588\u2580 \u2588\u2580\u2580 \u2588\u2580\u2588 \u2588\u2580\u2584\u2580\u2588 \u2588 \u2588 \u2580\u2584\u2580\n", scheme.ansi[4], false);
            append(text, " \u2588  \u2588\u2588\u2584 \u2588\u2580\u2584 \u2588 \u2580 \u2588 \u2588\u2584\u2588 \u2588 \u2588\n", scheme.ansi[5], false);
            append(text, getString(R.string.motd_subtitle) + "\n\n", dim, false);
            append(text, "pkg install python  ", scheme.ansi[6], false);
            append(text, getString(R.string.motd_install) + "\n", dim, false);
        } else {
            append(text, "Welcome to Termux!\n\n", scheme.foreground, false);
            append(text, "Docs:       https://termux.dev/docs\n", scheme.foreground, false);
        }
        appendPrompt(text, scheme, "~");
        append(text, "echo hello", scheme.foreground, false);
        return text;
    }

    private void appendPrompt(@NonNull SpannableStringBuilder text, @NonNull TerminalAppearance.ColorScheme scheme, @NonNull String directory) {
        if (text.length() > 0 && mChoices.prompt) text.append('\n');
        if (mChoices.prompt) {
            append(text, "\u256d\u2500 ", scheme.ansi[4], false);
            append(text, directory + "\n", scheme.ansi[4], true);
            append(text, "\u2570\u2500", scheme.ansi[4], false);
            append(text, "\u276f ", scheme.ansi[2], true);
        } else {
            append(text, directory, scheme.ansi[2], false);
            append(text, " $ ", scheme.foreground, false);
        }
    }

    private static void append(@NonNull SpannableStringBuilder builder, @NonNull String text, int color, boolean bold) {
        int start = builder.length();
        builder.append(text);
        builder.setSpan(new ForegroundColorSpan(color), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) builder.setSpan(new StyleSpan(Typeface.BOLD), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    @NonNull
    private static GradientDrawable roundedRect(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dpToPx(float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()));
    }

}
