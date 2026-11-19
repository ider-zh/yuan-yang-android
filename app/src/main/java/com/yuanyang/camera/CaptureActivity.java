package com.yuanyang.camera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Captures upright JPEGs with an optional same-aspect reference overlay. */
@SuppressWarnings("deprecation")
public final class CaptureActivity extends Activity implements SurfaceHolder.Callback {
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_REFERENCE = "reference";
    public static final String EXTRA_PROJECT = "project";
    public static final String EXTRA_ALBUM_ID = "albumId";
    public static final String RESULT_OPEN_ALBUM_LIST = "open_album_list";
    public static final String RESULT_OPEN_PROJECT = "open_project";
    public static final String RESULT_EXIT_APP = "exit_app";
    private static final int REQUEST_CAMERA = 72;
    private static final int NO_CAMERA = -1;
    private static final String CAMERA_FACING_KEY = "cameraFacing";
    private static final int WHITE = Color.rgb(246, 244, 238);
    private static final int MUTED = Color.rgb(181, 185, 173);
    private static final int ACCENT = Color.rgb(206, 235, 137);
    private static final int INK = Color.rgb(20, 24, 22);

    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private Camera camera;
    private SurfaceView surface;
    private AspectFrame finder;
    private FinderOverlay overlay;
    private Button shutter, switchCamera, recoveryAction, rearLenses, macroControl;
    private LinearLayout zoomRow;
    private SeekBar zoomSlider;
    private TextView zoomAmount;
    private String albumId;
    private List<Integer> zoomRatios = Collections.emptyList();
    private int zoomIndex;
    private boolean macroSupported, macroEnabled, updatingZoom;
    private final List<Integer> rearIds = new ArrayList<>();
    private final Map<Integer, Float> rearAngles = new HashMap<>();
    private final List<Integer> unavailableRearIds = new ArrayList<>();
    private LinearLayout overlayControls;
    private SeekBar overlaySlider;
    private TextView hint, overlayAmount;
    private Button albumListButton, currentAlbumButton;
    private File target, referenceFile, pendingPhoto;
    private Bitmap oldPhoto;
    private Bitmap savedReference;
    private SharedPreferences captureUi;
    private int overlayStrength = 45;
    private int captureSequence;
    private int backCameraId = NO_CAMERA, frontCameraId = NO_CAMERA, fallbackCameraId = NO_CAMERA;
    private int activeCameraId = NO_CAMERA;
    private int preferredFacing = Camera.CameraInfo.CAMERA_FACING_BACK;
    private boolean frontFacing;
    private long lastSwitchAt = -400;
    private boolean invalidReference;
    private String queuedNavigation;
    private boolean resumed, previewing, shooting, saving, saveFinished, saveSucceeded, permissionPending;
    private String saveErrorSummary;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(INK);
        getWindow().setNavigationBarColor(INK);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        captureUi = getSharedPreferences("captureUi", MODE_PRIVATE);
        albumId = getIntent().getStringExtra(EXTRA_ALBUM_ID);
        preferredFacing = captureUi.getInt(cameraSetting("facing"),
                captureUi.getInt(CAMERA_FACING_KEY, Camera.CameraInfo.CAMERA_FACING_BACK));
        overlayStrength = captureUi.getInt("overlayStrength", 45);
        // Migrate the former separate visibility toggle to the slider's zero.
        if (!captureUi.getBoolean("overlayVisible", true)) overlayStrength = 0;
        if (state != null) {
            overlayStrength = state.getInt("overlayStrength", overlayStrength);
            if (!state.getBoolean("overlayVisible", true)) overlayStrength = 0;
        }
        overlayStrength = Math.min(100, Math.max(0, overlayStrength));
        String label = getIntent().getStringExtra(EXTRA_PROJECT);
        String path = state != null ? state.getString("nextPath", getIntent().getStringExtra(EXTRA_PATH))
                : getIntent().getStringExtra(EXTRA_PATH);
        String prior = state != null ? state.getString("referencePath", getIntent().getStringExtra(EXTRA_REFERENCE))
                : getIntent().getStringExtra(EXTRA_REFERENCE);
        if (path != null && !path.isEmpty()) target = privateFile(path);
        if (target != null && target.isFile()) {
            prior = target.getPath(); // Process restored after a prior shot committed.
            target = nextPhoto(target);
        }
        if (state != null) {
            String pendingPath = state.getString("pendingPath");
            File candidate = pendingPath == null ? null : privateFile(pendingPath);
            if (candidate != null && candidate.isFile()) prior = candidate.getPath();
        }
        boolean requestedReference = prior != null && !prior.isEmpty();
        if (requestedReference) {
            referenceFile = privateFile(prior);
            if (referenceFile != null) oldPhoto = PhotoTools.load(referenceFile, 1440);
        }
        invalidReference = requestedReference && oldPhoto == null;
        buildUi(label == null || label.trim().isEmpty() ? "当前相册" : label);
        if (target == null) {
            hint.setText("没有可用的照片保存位置。请返回后重试。");
            return;
        }
        if (!hasCameraPermission() && state == null) requestCameraPermission();
        else if (!hasCameraPermission()) showPermissionAction();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("overlayStrength", overlayStrength);
        if (target != null) state.putString("nextPath", target.getPath());
        if (referenceFile != null) state.putString("referencePath", referenceFile.getPath());
        if (pendingPhoto != null) state.putString("pendingPath", pendingPhoto.getPath());
    }

    private File privateFile(String path) {
        try {
            File f = new File(path).getCanonicalFile();
            String p = f.getPath();
            String appFiles = getFilesDir().getCanonicalPath() + File.separator;
            String appCache = getCacheDir().getCanonicalPath() + File.separator;
            return p.startsWith(appFiles) || p.startsWith(appCache) ? f : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private void buildUi(String label) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(INK);
        root.setPadding(dp(16), dp(8), dp(16), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        albumListButton = action("所有相册");
        albumListButton.setSingleLine(true);
        albumListButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        albumListButton.setMinWidth(dp(96));
        albumListButton.setMaxWidth(dp(134));
        albumListButton.setOnClickListener(v -> leave(RESULT_OPEN_ALBUM_LIST));
        header.addView(albumListButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        rearLenses = action("镜头");
        rearLenses.setOnClickListener(v -> chooseRearLens());
        header.addView(rearLenses, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        macroControl = action("近摄");
        macroControl.setOnClickListener(v -> toggleMacro());
        header.addView(macroControl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        root.addView(header);

        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        stageParams.topMargin = dp(12);
        stageParams.bottomMargin = dp(11);
        root.addView(stage, stageParams);
        finder = new AspectFrame();
        stage.addView(finder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        surface = new SurfaceView(this);
        finder.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        surface.getHolder().addCallback(this);
        overlay = new FinderOverlay();
        finder.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hint = text(cameraHint(), 14, MUTED);
        hint.setGravity(Gravity.CENTER);
        hint.setMinHeight(dp(30));
        hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(hint);

        recoveryAction = action("");
        recoveryAction.setMinHeight(dp(48));
        recoveryAction.setOnClickListener(v -> {
            if (!hasCameraPermission()) {
                if (shouldOpenPermissionSettings()) {
                    Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null));
                    try { startActivity(settings); }
                    catch (RuntimeException e) { hint.setText("请从手机设置打开原样的相机权限。"); }
                } else requestCameraPermission();
            } else startCameraIfReady();
        });
        LinearLayout.LayoutParams recoveryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        recoveryParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(recoveryAction, recoveryParams);
        recoveryAction.setVisibility(View.GONE);

        overlayControls = new LinearLayout(this);
        overlayControls.setOrientation(LinearLayout.VERTICAL);
        TextView overlayHelp = text("叠影？滑块拖到最左就是隐藏", 13, MUTED);
        overlayHelp.setGravity(Gravity.CENTER);
        overlayControls.addView(overlayHelp, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout sliderRow = new LinearLayout(this);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        sliderRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        overlaySlider = new SeekBar(this);
        overlaySlider.setContentDescription("旧照片叠影透明度");
        overlaySlider.setMax(100);
        overlaySlider.setProgress(overlayStrength);
        overlaySlider.setProgressTintList(ColorStateList.valueOf(ACCENT));
        overlaySlider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        sliderRow.addView(overlaySlider, new LinearLayout.LayoutParams(0, dp(48), 1f));
        overlayAmount = text("", 13, WHITE);
        overlayAmount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        overlayAmount.setMinWidth(dp(42));
        sliderRow.addView(overlayAmount, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        overlayControls.addView(sliderRow);
        overlaySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                boolean visibilityChanged = (overlayStrength == 0) != (value == 0);
                overlayStrength = value;
                refreshOverlayControls();
                if (fromUser) rememberOverlay();
                if (visibilityChanged && previewing && !shooting && !saving) hint.setText(cameraHint());
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(overlayControls);
        zoomRow = new LinearLayout(this);
        zoomRow.setGravity(Gravity.CENTER_VERTICAL);
        zoomRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        zoomRow.addView(text("变焦", 13, MUTED));
        zoomSlider = new SeekBar(this);
        zoomSlider.setContentDescription("相机变焦");
        zoomSlider.setProgressTintList(ColorStateList.valueOf(ACCENT));
        zoomSlider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        zoomRow.addView(zoomSlider, new LinearLayout.LayoutParams(0, dp(48), 1f));
        zoomAmount = text("", 13, WHITE);
        zoomAmount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        zoomAmount.setMinWidth(dp(50));
        zoomRow.addView(zoomAmount, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        zoomSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                if (fromUser && !updatingZoom) changeZoom(value);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(zoomRow);

        shutter = new GlyphButton(GlyphButton.SHUTTER);
        shutter.setContentDescription(shutterLabel());
        shutter.setOnClickListener(v -> {
            if (!shooting && previewing) takePhoto();
        });
        FrameLayout actions = new FrameLayout(this);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        actionsParams.topMargin = dp(5);
        root.addView(actions, actionsParams);
        actions.addView(shutter, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));

        currentAlbumButton = new GlyphButton(GlyphButton.ALBUM);
        labelGlyph(currentAlbumButton, "打开当前相册：" + label);
        currentAlbumButton.setOnClickListener(v -> leave(RESULT_OPEN_PROJECT));
        actions.addView(currentAlbumButton, new FrameLayout.LayoutParams(
                dp(56), dp(56), Gravity.LEFT | Gravity.CENTER_VERTICAL));

        switchCamera = new GlyphButton(GlyphButton.FLIP);
        switchCamera.setOnClickListener(v -> switchFacing());
        actions.addView(switchCamera, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        refreshOverlayControls();
        updateSwitchControl();
        shutter.setEnabled(false);
        setContentView(root);
    }

    private void refreshOverlayControls() {
        overlayControls.setVisibility(oldPhoto != null ? View.VISIBLE : View.GONE);
        currentAlbumButton.invalidate();
        overlayAmount.setText(overlayStrength + "%");
        overlayAmount.setTextColor(overlayStrength > 0 ? WHITE : MUTED);
        overlay.opacity = overlayStrength / 100f;
        overlay.invalidate();
    }

    private void labelGlyph(Button button, String label) {
        button.setContentDescription(label);
        if (Build.VERSION.SDK_INT >= 26) button.setTooltipText(label);
    }

    private void rememberOverlay() {
        captureUi.edit().remove("overlayVisible")
                .putInt("overlayStrength", overlayStrength).apply();
    }

    private boolean hasCameraPermission() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= 23 && !permissionPending) {
            permissionPending = true;
            captureUi.edit().putBoolean("cameraPermissionRequested", true).apply();
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] granted) {
        super.onRequestPermissionsResult(code, permissions, granted);
        if (code != REQUEST_CAMERA) return;
        permissionPending = false;
        if (hasCameraPermission()) startCameraIfReady();
        else showPermissionAction();
    }

    private void showPermissionAction() {
        boolean settings = shouldOpenPermissionSettings();
        hint.setText(settings ? "请在系统设置里开启相机权限，再返回拍摄。" : "拍照需要相机权限，照片只保存在本机。");
        showRecovery(settings ? "打开权限设置" : "申请相机权限");
    }

    private boolean shouldOpenPermissionSettings() {
        return Build.VERSION.SDK_INT >= 23 && !permissionPending
                && captureUi.getBoolean("cameraPermissionRequested", false)
                && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (saveFinished) deliverSave();
        else if (target != null && !hasCameraPermission() && !permissionPending) showPermissionAction();
        else startCameraIfReady();
    }

    @Override protected void onPause() {
        resumed = false;
        if (!saving) shooting = false;
        closeCamera();
        super.onPause();
    }

    @Override protected void onDestroy() {
        closeCamera();
        writer.shutdown();
        super.onDestroy();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { startCameraIfReady(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { startCameraIfReady(); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (!saving) shooting = false;
        closeCamera();
    }

    private void startCameraIfReady() {
        if (!resumed || isFinishing() || camera != null || shooting || target == null || !hasCameraPermission()
                || !surface.getHolder().getSurface().isValid()) return;
        findCameras();
        int rear = rememberedRear();
        int preferred = preferredFacing == Camera.CameraInfo.CAMERA_FACING_FRONT ? frontCameraId : rear;
        if (openCamera(preferred)) return;
        if (backCameraId != preferred && openCamera(backCameraId)) return;
        if (frontCameraId != preferred && openCamera(frontCameraId)) return;
        if (fallbackCameraId != preferred && fallbackCameraId != backCameraId
                && fallbackCameraId != frontCameraId && openCamera(fallbackCameraId)) return;
        showRetry("无法打开相机，检查是否被其他应用占用。");
    }

    private void findCameras() {
        backCameraId = frontCameraId = fallbackCameraId = NO_CAMERA;
        rearIds.clear();
        int count;
        try { count = Camera.getNumberOfCameras(); }
        catch (RuntimeException e) { return; }
        for (int i = 0; i < count; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            try { Camera.getCameraInfo(i, info); }
            catch (RuntimeException e) { continue; }
            if (fallbackCameraId == NO_CAMERA) fallbackCameraId = i;
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                rearIds.add(i);
                if (backCameraId == NO_CAMERA) backCameraId = i;
            }
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT && frontCameraId == NO_CAMERA) frontCameraId = i;
        }
    }

    private boolean openCamera(int cameraId) {
        if (cameraId == NO_CAMERA) return false;
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraId, info);
            boolean front = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
            camera = Camera.open(cameraId);
            Camera cameraOpened = camera;
            Camera.Parameters p = camera.getParameters();
            Camera.Size photo = choosePhoto(p.getSupportedPictureSizes(), p.getPictureSize());
            Camera.Size preview = choosePreview(p.getSupportedPreviewSizes(), p.getPreviewSize(), photo);
            p.setPictureSize(photo.width, photo.height);
            p.setPreviewSize(preview.width, preview.height);
            p.setJpegQuality(92);
            int jpegRotation = (info.orientation + (front ? displayDegrees() : -displayDegrees()) + 360) % 360;
            // Camera1 mirrors front preview before rotation. Keep the original
            // JPEG unmirrored and mirror only the reference drawn over preview.
            int previewRotation = front ? (360 - jpegRotation) % 360 : jpegRotation;
            p.setRotation(jpegRotation);
            if (!front) {
                rearAngles.put(cameraId, viewAngle(p));
                unavailableRearIds.remove(Integer.valueOf(cameraId));
            }
            macroSupported = !front && supportsMacro(p);
            macroEnabled = macroSupported && captureUi.getBoolean(cameraSetting("macro/" + cameraId), false);
            applyFocus(p, macroEnabled);
            zoomRatios = readZoomRatios(p);
            zoomIndex = 0;
            if (!zoomRatios.isEmpty()) {
                int ratio = captureUi.getInt(cameraSetting("zoom/" + cameraId), 100);
                zoomIndex = CameraOptions.zoomIndex(zoomRatios, ratio);
                p.setZoom(zoomIndex);
            }
            try {
                camera.setParameters(p);
            } catch (RuntimeException rejected) {
                // Some vendors advertise unsupported combinations. Keep a usable preview
                // even if the stored optional parameters cannot be restored.
                if (zoomIndex == 0 && !macroEnabled) throw rejected;
                macroEnabled = false;
                zoomIndex = 0;
                if (!zoomRatios.isEmpty()) p.setZoom(0);
                applyFocus(p, false);
                camera.setParameters(p);
            }
            camera.setDisplayOrientation(previewRotation);
            finder.ratio = previewRotation % 180 == 0 ? (float) preview.width / preview.height : (float) preview.height / preview.width;
            finder.requestLayout();
            camera.setPreviewDisplay(surface.getHolder());
            camera.setErrorCallback((error, instance) -> {
                if (camera != cameraOpened) return;
                closeCamera();
                if (!saving) {
                    shooting = false;
                    showRetry("相机中断，请重试。");
                }
            });
            activeCameraId = cameraId;
            frontFacing = front;
            startPreview();
            overlay.invalidate();
            return true;
        } catch (IOException | RuntimeException e) {
            closeCamera();
            return false;
        }
    }

    private void switchFacing() {
        long now = SystemClock.elapsedRealtime();
        if (!resumed || isFinishing() || !previewing || shooting || saving || queuedNavigation != null
                || now - lastSwitchAt < 350 || backCameraId == NO_CAMERA || frontCameraId == NO_CAMERA) return;
        switchLens(frontFacing ? rememberedRear() : frontCameraId);
    }

    private String cameraSetting(String option) { return CameraOptions.albumKey(albumId, option); }

    private int rememberedRear() {
        int id = captureUi.getInt(cameraSetting("rear"), backCameraId);
        return rearIds.contains(id) ? id : backCameraId;
    }

    private boolean canChangeCamera() {
        return resumed && !isFinishing() && previewing && !shooting && !saving && queuedNavigation == null;
    }

    private void switchLens(int requested) {
        if (!canChangeCamera() || requested == NO_CAMERA || requested == activeCameraId) return;
        int before = activeCameraId;
        shutter.setEnabled(false);
        closeCamera();
        if (openCamera(requested)) {
            preferredFacing = frontFacing ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;
            SharedPreferences.Editor editor = captureUi.edit().putInt(cameraSetting("facing"), preferredFacing);
            if (!frontFacing) {
                editor.putInt(cameraSetting("rear"), requested);
                editor.putString(cameraSetting("rearLabel"), rearLabel(requested));
            }
            editor.apply();
        } else if (openCamera(before)) {
            hint.setText("切换失败，已恢复原摄像头。");
        } else {
            startCameraIfReady();
            if (!previewing) showRetry("切换失败，请重试相机。");
        }
        lastSwitchAt = SystemClock.elapsedRealtime();
    }

    private void chooseRearLens() {
        if (!canChangeCamera() || frontFacing || rearIds.size() < 2) return;
        int before = activeCameraId;
        // Opening other Camera1 devices can require exclusive access. Probe only
        // on demand and always restore the previous preview before showing choices.
        if (!rearAngles.keySet().containsAll(rearIds)) {
            closeCamera();
            for (int id : rearIds) {
                if (rearAngles.containsKey(id) || unavailableRearIds.contains(id)) continue;
                Camera probe = null;
                try {
                    probe = Camera.open(id);
                    rearAngles.put(id, viewAngle(probe.getParameters()));
                } catch (RuntimeException unavailable) {
                    unavailableRearIds.add(id);
                } finally {
                    if (probe != null) try { probe.release(); } catch (RuntimeException ignored) {}
                }
            }
            if (!openCamera(before)) {
                startCameraIfReady();
                return;
            }
        }
        List<Integer> choices = new ArrayList<>(rearIds);
        choices.removeAll(unavailableRearIds);
        if (choices.size() < 2) {
            updateSwitchControl();
            hint.setText("其它后置镜头当前不可用。");
            return;
        }
        String[] labels = new String[choices.size()];
        for (int i = 0; i < labels.length; i++) {
            int id = choices.get(i);
            labels[i] = rearLabel(id);
        }
        new AlertDialog.Builder(this).setTitle("后置镜头")
                .setSingleChoiceItems(labels, choices.indexOf(activeCameraId), (dialog, index) -> {
                    dialog.dismiss();
                    switchLens(choices.get(index));
                }).setNegativeButton("取消", null).show();
    }

    private String rearLabel(int id) {
        return CameraOptions.rearLabel(rearIds.indexOf(id), rearAngles.getOrDefault(backCameraId, -1f),
                rearAngles.getOrDefault(id, -1f));
    }

    private static boolean supportsMacro(Camera.Parameters p) {
        List<String> modes = p.getSupportedFocusModes();
        if (modes == null || !modes.contains(Camera.Parameters.FOCUS_MODE_MACRO)) return false;
        for (String mode : modes) if (!Camera.Parameters.FOCUS_MODE_MACRO.equals(mode)) return true;
        return false;
    }

    private static float viewAngle(Camera.Parameters p) {
        try { return p.getHorizontalViewAngle(); }
        catch (RuntimeException invalid) { return -1f; }
    }

    private static void applyFocus(Camera.Parameters p, boolean macro) {
        List<String> modes = p.getSupportedFocusModes();
        if (modes == null) return;
        if (macro && modes.contains(Camera.Parameters.FOCUS_MODE_MACRO)) {
            p.setFocusMode(Camera.Parameters.FOCUS_MODE_MACRO);
        } else if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
            p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
        } else if (modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
            p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
        } else {
            for (String mode : modes) if (!Camera.Parameters.FOCUS_MODE_MACRO.equals(mode)) {
                p.setFocusMode(mode);
                return;
            }
        }
    }

    private static List<Integer> readZoomRatios(Camera.Parameters p) {
        try {
            return p.isZoomSupported() ? CameraOptions.validatedZoomRatios(p.getZoomRatios(), p.getMaxZoom())
                    : Collections.emptyList();
        } catch (RuntimeException invalid) {
            return Collections.emptyList();
        }
    }

    private void changeZoom(int index) {
        if (!canChangeCamera() || index < 0 || index >= zoomRatios.size() || index == zoomIndex) return;
        try {
            Camera.Parameters p = camera.getParameters();
            p.setZoom(index);
            camera.setParameters(p);
            int actual = camera.getParameters().getZoom();
            if (actual >= 0 && actual < zoomRatios.size()) {
                zoomIndex = actual;
                captureUi.edit().putInt(cameraSetting("zoom/" + activeCameraId), zoomRatios.get(actual)).apply();
            }
        } catch (RuntimeException rejected) {
            hint.setText("这个变焦档位不可用。");
        }
        updateSwitchControl();
    }

    private void toggleMacro() {
        if (!canChangeCamera() || !macroSupported) return;
        try {
            boolean desired = !macroEnabled;
            Camera.Parameters p = camera.getParameters();
            applyFocus(p, desired);
            if (desired != Camera.Parameters.FOCUS_MODE_MACRO.equals(p.getFocusMode())) return;
            camera.setParameters(p);
            macroEnabled = Camera.Parameters.FOCUS_MODE_MACRO.equals(camera.getParameters().getFocusMode());
            captureUi.edit().putBoolean(cameraSetting("macro/" + activeCameraId), macroEnabled).apply();
        } catch (RuntimeException rejected) {
            hint.setText("当前镜头无法切换近摄。");
        }
        updateSwitchControl();
    }

    private void updateSwitchControl() {
        if (switchCamera == null) return;
        boolean both = backCameraId != NO_CAMERA && frontCameraId != NO_CAMERA;
        switchCamera.setVisibility(both ? View.VISIBLE : View.GONE);
        switchCamera.setEnabled(both && previewing && !shooting && !saving && resumed);
        String action = frontFacing ? "切换到后置" : "切换到前置";
        labelGlyph(switchCamera, action + "摄像头");
        boolean ready = canChangeCamera();
        int selectableRear = 0;
        for (int id : rearIds) if (!unavailableRearIds.contains(id)) selectableRear++;
        boolean hasLensOptions = previewing && !frontFacing && selectableRear > 1;
        rearLenses.setVisibility(hasLensOptions ? View.VISIBLE : View.GONE);
        rearLenses.setEnabled(ready);
        if (hasLensOptions) {
            String label = rearLabel(activeCameraId);
            if (!rearAngles.containsKey(backCameraId) && rememberedRear() == activeCameraId) {
                label = captureUi.getString(cameraSetting("rearLabel"), label);
            }
            rearLenses.setText(label.startsWith("广角") ? "广角" : label.startsWith("窄视角") ? "窄视角" : "镜头");
            rearLenses.setContentDescription("选择后置镜头，当前：" + label);
        }
        macroControl.setVisibility(previewing && macroSupported ? View.VISIBLE : View.GONE);
        macroControl.setEnabled(ready);
        macroControl.setSelected(macroEnabled);
        macroControl.setText(macroEnabled ? "近摄：开" : "近摄");
        // Hold existing geometry while capture and saving temporarily suspend preview.
        boolean hasZoom = activeCameraId != NO_CAMERA && !zoomRatios.isEmpty();
        zoomRow.setVisibility(hasZoom ? View.VISIBLE : View.GONE);
        zoomSlider.setEnabled(ready && hasZoom);
        if (hasZoom) {
            updatingZoom = true;
            zoomSlider.setMax(zoomRatios.size() - 1);
            zoomSlider.setProgress(zoomIndex);
            zoomAmount.setText(java.text.NumberFormat.getNumberInstance().format(zoomRatios.get(zoomIndex) / 100d) + "×");
            updatingZoom = false;
        }
    }

    private void startPreview() {
        camera.startPreview();
        previewing = true;
        recoveryAction.setVisibility(View.GONE);
        labelGlyph(shutter, shutterLabel());
        shutter.setEnabled(true);
        hint.setText(cameraHint());
        updateSwitchControl();
    }

    private int displayDegrees() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    private static Camera.Size choosePhoto(List<Camera.Size> all, Camera.Size fallback) {
        if (all == null || all.isEmpty()) return fallback;
        Camera.Size best = fallback;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Camera.Size s : all) {
            double ratio = (double) s.width / s.height;
            double area = (double) s.width * s.height;
            // Prefer a native four-to-three full frame, bounded around four megapixels.
            double score = Math.abs(ratio - 4d / 3d) * 30 + Math.abs(Math.log(area / 4000000d));
            if (area < 1000000) score += 3;
            if (score < bestScore) { best = s; bestScore = score; }
        }
        return best;
    }

    private static Camera.Size choosePreview(List<Camera.Size> all, Camera.Size fallback, Camera.Size photo) {
        if (all == null || all.isEmpty()) return fallback;
        double photoRatio = (double) photo.width / photo.height;
        Camera.Size best = fallback;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Camera.Size s : all) {
            double ratio = (double) s.width / s.height;
            double area = (double) s.width * s.height;
            double score = Math.abs(ratio - photoRatio) * 80 + Math.abs(Math.log(area / 1000000d));
            if (score < bestScore) { best = s; bestScore = score; }
        }
        return best;
    }

    private void takePhoto() {
        Camera current = camera;
        if (current == null || !previewing || shooting) return;
        int shot = ++captureSequence;
        shooting = true;
        shutter.setEnabled(false);
        updateSwitchControl();
        hint.setText("正在拍摄…");
        String focusMode;
        try {
            focusMode = current.getParameters().getFocusMode();
        } catch (RuntimeException e) {
            triggerShutter(current, shot);
            return;
        }
        if (Camera.Parameters.FOCUS_MODE_AUTO.equals(focusMode)
                || Camera.Parameters.FOCUS_MODE_MACRO.equals(focusMode)) {
            try {
                current.autoFocus((success, instance) -> triggerShutter(instance, shot));
                shutter.postDelayed(() -> triggerShutter(current, shot), 850);
            } catch (RuntimeException e) {
                triggerShutter(current, shot);
            }
        } else {
            triggerShutter(current, shot);
        }
    }

    private void triggerShutter(Camera current, int shot) {
        if (camera != current || shot != captureSequence || !previewing || !shooting) return;
        previewing = false; // Also prevents the autofocus timeout from taking a second photo.
        try {
            current.takePicture(null, null, (data, instance) -> {
                if (camera != instance || shot != captureSequence || !shooting || isFinishing()) return;
                if (data == null || data.length < 4 || (data[0] & 255) != 0xff || (data[1] & 255) != 0xd8) {
                    recoverAfterCapture("相机没有生成照片，请重新拍摄。");
                    return;
                }
                saving = true;
                File destination = target;
                pendingPhoto = destination;
                target = nextPhoto(destination);
                hint.setText("正在保存…");
                writer.execute(() -> {
                    boolean ok = false;
                    String error = "照片未能保存，请检查存储空间后重试。";
                    Bitmap decoded = null;
                    try {
                        decoded = saveJpeg(data, destination);
                        ok = true;
                    } catch (InvalidPhotoException ignored) {
                        error = "照片无法读取，请重新拍摄。";
                    } catch (IOException | RuntimeException ignored) {}
                    final boolean success = ok;
                    final Bitmap result = decoded;
                    final String reason = error;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        saveFinished = true;
                        saveSucceeded = success;
                        savedReference = result;
                        saveErrorSummary = reason;
                        if (resumed) deliverSave();
                    });
                });
            });
            shutter.postDelayed(() -> {
                if (camera == current && shot == captureSequence && shooting && !saving)
                    recoverAfterCapture("相机响应超时，请重新拍摄。");
            }, 8000);
        } catch (RuntimeException e) {
            recoverAfterCapture("拍摄失败，请重试。");
        }
    }

    private static File nextPhoto(File previous) {
        return new File(previous.getParentFile(), UUID.randomUUID() + ".jpg");
    }

    private static final class InvalidPhotoException extends IOException {}

    private Bitmap saveJpeg(byte[] data, File destination) throws IOException {
        File folder = destination.getParentFile();
        if (folder == null || (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()))
            throw new IOException("Cannot create photo directory");
        File temp = File.createTempFile("capture-", ".pending", folder);
        try {
            try (FileOutputStream stream = new FileOutputStream(temp)) {
                stream.write(data);
                stream.getFD().sync();
            }
            Bitmap decoded = PhotoTools.load(temp, 1440);
            if (decoded == null) throw new InvalidPhotoException();
            if (destination.exists()) throw new IOException("Timeline photos are immutable");
            if (!temp.renameTo(destination)) throw new IOException("Cannot publish photo");
            return decoded;
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private void deliverSave() {
        saving = false;
        saveFinished = false;
        if (queuedNavigation != null) {
            if (!saveSucceeded) Toast.makeText(this, saveErrorSummary, Toast.LENGTH_SHORT).show();
            finishNavigation(queuedNavigation);
            return;
        }
        if (saveSucceeded) {
            shooting = false;
            referenceFile = pendingPhoto;
            pendingPhoto = null;
            oldPhoto = savedReference;
            savedReference = null;
            invalidReference = false;
            refreshOverlayControls();
            if (camera != null && surface.getHolder().getSurface().isValid()) {
                try { startPreview(); }
                catch (RuntimeException e) { closeCamera(); }
            } else closeCamera();
            if (!previewing) startCameraIfReady();
            if (previewing) hint.setText("已保存。 " + cameraHint());
        } else {
            pendingPhoto = null;
            recoverAfterCapture(saveErrorSummary);
        }
    }

    private void recoverAfterCapture(String reason) {
        closeCamera();
        shooting = false;
        startCameraIfReady();
        hint.setText(reason);
        if (!previewing) showRetry(reason);
    }

    private void showRetry(String reason) {
        hint.setText(reason);
        showRecovery("重试相机");
    }

    private void showRecovery(String label) {
        shutter.setEnabled(false);
        recoveryAction.setText(label);
        recoveryAction.setVisibility(View.VISIBLE);
    }

    private void closeCamera() {
        previewing = false;
        activeCameraId = NO_CAMERA;
        updateSwitchControl();
        Camera previous = camera;
        camera = null;
        if (previous == null) return;
        try { previous.setErrorCallback(null); } catch (RuntimeException ignored) {}
        try { previous.stopPreview(); } catch (RuntimeException ignored) {}
        try { previous.release(); } catch (RuntimeException ignored) {}
    }

    @Override public void onBackPressed() { leave(RESULT_EXIT_APP); }

    private void leave(String destination) {
        if (isFinishing() || queuedNavigation != null) return;
        if (saving) {
            queuedNavigation = destination;
            albumListButton.setEnabled(false);
            currentAlbumButton.setEnabled(false);
            hint.setText(RESULT_EXIT_APP.equals(destination)?"正在保存，完成后退出…":"正在保存，完成后打开相册…");
            return;
        }
        finishNavigation(destination);
    }

    private void finishNavigation(String destination) {
        shooting = false;
        closeCamera();
        setResult(RESULT_OK, new Intent().putExtra(destination, true));
        finish();
    }

    private String shutterLabel() {
        return oldPhoto != null ? "复拍" : "拍照";
    }

    private String cameraHint() {
        String status;
        if (oldPhoto != null) status = overlayStrength > 0 ? "移动手机，手动对齐旧照。" : "叠影已隐藏，拍照仍会保存到相册。";
        else if (invalidReference) status = "上一张照片无法读取，可继续拍摄。";
        else status = "照片保存在当前相册中。";
        return frontFacing ? "前置预览为镜像，保存不镜像。\n" + status : status;
    }

    private int dp(float n) { return Math.round(getResources().getDisplayMetrics().density * n); }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button action(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(WHITE);
        button.setTextSize(14);
        button.setMinimumWidth(0);
        button.setPadding(dp(9), 0, dp(9), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(new ColorStateList(new int[][]{{android.R.attr.state_selected}, {}},
                new int[]{Color.rgb(59, 76, 48), Color.rgb(42, 55, 47)}));
        bg.setCornerRadius(dp(13));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        return button;
    }

    /** Local vectors keep the three fixed touch targets independent of font scale. */
    private final class GlyphButton extends Button {
        static final int SHUTTER = 0, ALBUM = 1, FLIP = 2;
        final int glyph;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();

        GlyphButton(int glyph) {
            super(CaptureActivity.this);
            this.glyph = glyph;
            setPadding(0, 0, 0, 0);
            setMinWidth(0);
            setMinHeight(0);
            setMinimumWidth(0);
            setMinimumHeight(0);
            setFocusable(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            if (glyph == SHUTTER) bg.setColor(Color.TRANSPARENT);
            else bg.setColor(new ColorStateList(new int[][]{{android.R.attr.state_selected}, {}},
                    new int[]{Color.rgb(59, 76, 48), Color.rgb(42, 55, 47)}));
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.OVAL);
            mask.setColor(Color.WHITE);
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x38FFFFFF), bg, mask));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (glyph == SHUTTER) {
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                paint.setColor(isEnabled() ? WHITE : MUTED);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(3));
                canvas.drawCircle(cx, cy, dp(32), paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(isEnabled() ? ACCENT : Color.rgb(91, 103, 80));
                canvas.drawCircle(cx, cy, dp(25), paint);
                return;
            }
            if (glyph == ALBUM && oldPhoto != null && !oldPhoto.isRecycled()) {
                float width = dp(44);
                float left = (getWidth() - width) / 2f, top = (getHeight() - width) / 2f;
                RectF tile = new RectF(left, top, left + width, top + width);
                float radius = dp(10);
                path.reset();
                path.addRoundRect(tile, radius, radius, Path.Direction.CW);
                int clip = canvas.save();
                canvas.clipPath(path);
                float scale = Math.max(width / oldPhoto.getWidth(), width / oldPhoto.getHeight());
                float halfWidth = oldPhoto.getWidth() * scale / 2f;
                float halfHeight = oldPhoto.getHeight() * scale / 2f;
                paint.setStyle(Paint.Style.FILL);
                paint.setFilterBitmap(true);
                canvas.drawBitmap(oldPhoto, null, new RectF(tile.centerX() - halfWidth, tile.centerY() - halfHeight,
                        tile.centerX() + halfWidth, tile.centerY() + halfHeight), paint);
                canvas.restoreToCount(clip);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1));
                paint.setColor(isEnabled() ? WHITE : MUTED);
                canvas.drawRoundRect(tile, radius, radius, paint);
                return;
            }
            int save = canvas.save();
            float px = dp(26);
            canvas.translate((getWidth() - px) / 2f, (getHeight() - px) / 2f);
            canvas.scale(px / 24f, px / 24f);
            paint.setColor(isEnabled() ? WHITE : MUTED);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            if (glyph == ALBUM) {
                canvas.drawRoundRect(6f, 7f, 21.5f, 20.5f, 2f, 2f, paint);
                path.reset();
                path.moveTo(2.5f, 16f);
                path.lineTo(2.5f, 5.5f);
                path.quadTo(2.5f, 3.5f, 4.5f, 3.5f);
                path.lineTo(17f, 3.5f);
                canvas.drawPath(path, paint);
                path.reset();
                path.moveTo(8.5f, 17.5f);
                path.lineTo(12.5f, 13.5f);
                path.lineTo(15f, 16f);
                path.lineTo(17f, 14f);
                path.lineTo(19f, 16f);
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(17.6f, 10.8f, 1.15f, paint);
            } else {
                // Camera outline plus opposing arrows, in the familiar Material camera-switch form.
                path.reset();
                path.moveTo(2.5f, 8.5f);
                path.quadTo(2.5f, 7f, 4f, 7f);
                path.lineTo(7f, 7f);
                path.lineTo(9f, 4.5f);
                path.lineTo(15f, 4.5f);
                path.lineTo(17f, 7f);
                path.lineTo(20f, 7f);
                path.quadTo(21.5f, 7f, 21.5f, 8.5f);
                path.lineTo(21.5f, 18f);
                path.quadTo(21.5f, 19.5f, 20f, 19.5f);
                path.lineTo(4f, 19.5f);
                path.quadTo(2.5f, 19.5f, 2.5f, 18f);
                path.close();
                canvas.drawPath(path, paint);
                canvas.drawLine(8f, 11f, 16f, 11f, paint);
                canvas.drawLine(16f, 11f, 14f, 9f, paint);
                canvas.drawLine(16f, 15.5f, 8f, 15.5f, paint);
                canvas.drawLine(8f, 15.5f, 10f, 17.5f, paint);
            }
            canvas.restoreToCount(save);
        }
    }

    private final class AspectFrame extends FrameLayout {
        float ratio = 3f / 4f;
        AspectFrame() { super(CaptureActivity.this); }
        @Override protected void onMeasure(int width, int height) {
            int w = MeasureSpec.getSize(width), h = MeasureSpec.getSize(height);
            if (w > 0 && h > 0) {
                if ((float) w / h > ratio) w = (int) (h * ratio);
                else h = (int) (w / ratio);
            }
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
    }

    private final class FinderOverlay extends View {
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint guides = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private float opacity = 0.45f;
        FinderOverlay() { super(CaptureActivity.this); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (oldPhoto != null && opacity > 0) {
                imagePaint.setAlpha(Math.round(opacity * 255));
                bounds.set(0f, 0f, w, h);
                int save = c.save();
                if (frontFacing) c.scale(-1f, 1f, w / 2f, h / 2f);
                PhotoTools.drawCover(c, oldPhoto, bounds, imagePaint);
                c.restoreToCount(save);
            }
            guides.setColor(0x60FFFFFF);
            guides.setStrokeWidth(dp(1));
            for (int i = 1; i < 3; i++) {
                c.drawLine(w * i / 3, 0, w * i / 3, h, guides);
                c.drawLine(0, h * i / 3, w, h * i / 3, guides);
            }
            guides.setColor(0xB4FFFFFF);
            float center = dp(7);
            c.drawLine(w / 2 - center, h / 2, w / 2 + center, h / 2, guides);
            c.drawLine(w / 2, h / 2 - center, w / 2, h / 2 + center, guides);
        }
    }
}
