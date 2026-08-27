package dev.rocky.comcitime;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private Prefs prefs;

    private LinearLayout[] pages;
    private static final String[] TAB_NAMES = {"시간표", "급식", "지도", "설정"};
    private static final String[] TAB_ICONS = {"📅", "🍱", "🗺", "⚙️"};
    private static final int TAB_TIMETABLE = 0, TAB_MEAL = 1, TAB_MAP = 2, TAB_SETTINGS = 3;

    private TextView classHeaderLabel;
    private TextView teacherModeIndicator;
    private LinearLayout nowPanel;
    private LinearLayout weekSectionsContainer;
    private String viewingTeacherName = null;
    private boolean teacherViewLocked = false;
    private int browseClassNum = -1;
    private final List<WeekSection> weekSections = new ArrayList<>();
    private final Handler nowPanelHandler = new Handler();
    private Button prevWeekBtn;
    private int loadGeneration = 0;

    private static class WeekSection {
        Timetable tt;
        LinearLayout card;
        TableLayout grid;
    }

    private EditText searchInput;
    private LinearLayout searchResults;
    private TextView selectedSchoolLabel;
    private AutoCompleteTextView gradeAuto, classAuto;
    private LinearLayout savedClassesList;
    private LinearLayout colorsList;
    private String pendingSchoolCode = "", pendingSchoolName = "";
    private CheckBox notifyChangeCheck, notifyPeriodCheck, notifyMorningCheck, liveNotifyCheck;
    private CheckBox solidColorCheck;
    private CheckBox alwaysRecordCheck;
    private EditText morningTimeInput;
    private EditText[] periodInputs = new EditText[8];
    private EditText neisKeyInput;
    private CheckBox teacherModeCheck;
    private EditText teacherNameInput;
    private LinearLayout studentSettingsBox, teacherSettingsBox;
    private View onboardingStep1, onboardingStep2, onboardingStep3, onboardingStep4;
    private TextView onboardingSelectedSchoolLabel;
    private CheckBox onboardingIsTeacherModeCheck;
    private LinearLayout onboardingStudentFields, onboardingTeacherFields;
    private EditText onboardingTeacherNameInput;
    private AutoCompleteTextView onboardingGradeAuto, onboardingClassAuto;

    private TextView mealStatusText;
    private LinearLayout mealContent;

    private TextView mappingStatusText, mappingCountsText, mappingSensorText, mappingStrideText, mappingApRssiText;
    private Button mappingGrantBtn, mappingBatteryBtn;
    private EditText mappingFloorInput, mappingLabelInput;
    private OrientationGizmoView mappingGizmoView;
    private MappingPathView mappingPathView;
    private SparklineView accelGraph, gyroGraph, magGraph, pressureGraph, rssiGraph, gyroYawGraph;
    private Runnable pendingMappingStart;
    private static final int REQ_MAPPING_PERMS = 2;
    private final Handler mappingTickHandler = new Handler();
    private Runnable mappingTick;

    private FrameLayout onboardingOverlay;

    private static final String[] DOW_SHORT = {"", "월", "화", "수", "목", "금"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.init(this);
        prefs = new Prefs(this);
        NotificationHelper.ensureChannels(this);

        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(UiKit.BG);

        LinearLayout outerCol = new LinearLayout(this);
        outerCol.setOrientation(LinearLayout.VERTICAL);

        pages = new LinearLayout[]{buildTimetablePage(), buildMealPage(), buildMapPage(), buildSettingsPage()};
        FrameLayout container = new FrameLayout(this);
        for (LinearLayout p : pages) {
            container.addView(p, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        outerCol.addView(container, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // Explicit fixed height, not WRAP_CONTENT: an earlier version of
        // this bar hit the classic Android wrap_content-parent/
        // match_parent-child sizing trap and ballooned to fill the screen.
        outerCol.addView(buildBottomNav(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        rootFrame.addView(outerCol, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootFrame.addView(buildOnboardingOverlay(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootFrame);
        loadPrefsIntoUi();
        showPage(prefs.schoolCode().isEmpty() ? TAB_SETTINGS : TAB_TIMETABLE);

        if (prefs.onboardingDone()) {
            onboardingOverlay.setVisibility(View.GONE);
            startMappingServiceIfPermitted();
        } else {
            onboardingOverlay.setVisibility(View.VISIBLE);
        }
    }

    // Indoor-mapping data collection is an always-on background service,
    // not a manual toggle -- it runs for as long as the user has consented
    // AND actually granted the permissions it needs. Called once right
    // after onboarding consent (after requesting those permissions) and
    // again on every app launch while already consented, in case the
    // service got killed by the OS or permissions were only granted later.
    private void startMappingServiceIfPermitted() {
        boolean fineLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean activityRecognition = Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        if (fineLocation && activityRecognition) {
            startForegroundService(new Intent(this, MappingService.class));
            MappingWatchdogReceiver.schedule(this);
        }
    }

    // Whether the OS already exempts this app from Doze/App Standby battery
    // throttling -- if not, the mapping background service is liable to get
    // paused by the system during long idle stretches (screen off for
    // hours, e.g. a school day) regardless of the START_STICKY/watchdog
    // recovery paths, since those only bring it back once the OS lets it
    // run again.
    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "이 기기에서는 배터리 설정 화면을 열 수 없어요.", Toast.LENGTH_SHORT).show();
        }
    }

    private static final int REQ_EXPORT_MOTION = 3;
    private static final int REQ_EXPORT_ALL = 4;

    // Lets the user record indoor-mapping motion data and export it under a
    // filename they pick/edit themselves -- ACTION_CREATE_DOCUMENT opens the
    // system "save as" picker (its filename field pre-filled with a
    // timestamped suggestion, fully editable) so no storage permission is
    // needed and this works the same way across every supported API level.
    private void exportMappingCsv() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        String suggested = "comcitime_motion_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(new java.util.Date()) + ".csv";
        intent.putExtra(Intent.EXTRA_TITLE, suggested);
        try {
            startActivityForResult(intent, REQ_EXPORT_MOTION);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "파일 저장 앱을 찾을 수 없어요.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        boolean isExport = requestCode == REQ_EXPORT_MOTION || requestCode == REQ_EXPORT_ALL;
        if (!isExport || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        android.net.Uri uri = data.getData();
        boolean motionOnly = requestCode == REQ_EXPORT_MOTION;
        Toast.makeText(this, "내보내는 중...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String error = null;
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("no output stream");
                java.io.Writer writer = new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8);
                if (motionOnly) {
                    new MappingDb(this).exportMotionCsv(writer);
                } else {
                    // Whole-dataset JSON dump, plus whatever the running
                    // collector holds only in memory (see addLiveSensorData()).
                    JSONObject all = new MappingDb(this).exportAllData();
                    addLiveSensorData(all);
                    writer.write(all.toString(2));
                }
                writer.flush();
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String err = error;
            runOnUiThread(() -> Toast.makeText(this,
                    err == null ? "내보냈어요." : "내보내기에 실패했어요: " + err,
                    err == null ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show());
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MAPPING_PERMS) return;
        boolean allGranted = grantResults.length > 0;
        for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
        if (allGranted && pendingMappingStart != null) {
            pendingMappingStart.run();
        } else {
            Toast.makeText(this, "위치/동작 권한을 허용해야 지도 데이터를 수집할 수 있어요.", Toast.LENGTH_SHORT).show();
        }
        pendingMappingStart = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        nowPanelHandler.removeCallbacksAndMessages(null);
        mappingTickHandler.removeCallbacksAndMessages(null);
    }

    private LinearLayout[] tabPills;
    private TextView[] tabLabels;

    private View buildBottomNav() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(UiKit.SURFACE);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        tabPills = new LinearLayout[TAB_NAMES.length];
        tabLabels = new TextView[TAB_NAMES.length];
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int idx = i;
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            col.setClickable(true);
            col.setFocusable(true);
            col.setOnClickListener(v -> showPage(idx));
            UiKit.attachBouncyPress(col);

            LinearLayout pill = new LinearLayout(this);
            pill.setOrientation(LinearLayout.VERTICAL);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(14), dp(6), dp(14), dp(6));

            TextView icon = new TextView(this);
            icon.setText(TAB_ICONS[i]);
            icon.setTextSize(16);
            icon.setGravity(Gravity.CENTER);
            pill.addView(icon);

            TextView label = new TextView(this);
            label.setText(TAB_NAMES[i]);
            label.setTextSize(11);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.topMargin = dp(1);
            pill.addView(label, labelLp);

            col.addView(pill);
            tabPills[i] = pill;
            tabLabels[i] = label;
            bar.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        return bar;
    }

    private void updateBottomNavHighlight(int index) {
        for (int i = 0; i < tabPills.length; i++) {
            boolean active = i == index;
            tabPills[i].setBackground(active ? UiKit.pillFilled(UiKit.ACCENT) : null);
            tabLabels[i].setTextColor(active ? UiKit.ACCENT_TEXT : UiKit.TEXT_SECONDARY);
            tabLabels[i].setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void showPage(int index) {
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        updateBottomNavHighlight(index);
        nowPanelHandler.removeCallbacksAndMessages(null);
        mappingTickHandler.removeCallbacksAndMessages(null);
        if (index == TAB_TIMETABLE) { loadAllWeeks(); tickNowPanel(); }
        if (index == TAB_MEAL) refreshMeal();
        if (index == TAB_MAP) {
            refreshMappingStatus();
            startMappingTick();
        }
        if (index == TAB_SETTINGS) refreshColorsList();
    }

    private FrameLayout buildOnboardingOverlay() {
        onboardingOverlay = new FrameLayout(this);
        onboardingOverlay.setBackgroundColor(UiKit.BG);
        
        onboardingStep1 = buildStep1();
        onboardingStep2 = buildStep2();
        onboardingStep3 = buildStep3();
        onboardingStep4 = buildStep4();
        
        onboardingOverlay.addView(onboardingStep1);
        onboardingOverlay.addView(onboardingStep2);
        onboardingOverlay.addView(onboardingStep3);
        onboardingOverlay.addView(onboardingStep4);
        
        onboardingStep2.setVisibility(View.GONE);
        onboardingStep3.setVisibility(View.GONE);
        onboardingStep4.setVisibility(View.GONE);
        
        return onboardingOverlay;
    }

    private View buildStep1() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(60), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("환영합니다!");
        title.setTextColor(UiKit.TEXT_PRIMARY);
        title.setTextSize(32);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("컴시간알리미+와 함께\n스마트한 학교 생활을 시작해 보세요.");
        UiKit.styleBody(sub);
        sub.setPadding(0, dp(12), 0, dp(40));
        root.addView(sub);

        LinearLayout infoCard = card();
        infoCard.addView(onboardingLine("📅 시간표 확인", "학교 시간표를 실시간으로 확인하고 변동 사항을 즉시 알려드려요."));
        infoCard.addView(onboardingLine("🍱 급식 정보", "오늘의 메뉴를 위젯과 알림으로 간편하게 확인하세요."));
        infoCard.addView(onboardingLine("🧭 실내 지도", "학교 내에서의 위치를 3D로 파악하고 기록할 수 있어요."));
        root.addView(infoCard, cardLp());

        Button nextBtn = new Button(this);
        nextBtn.setText("시작하기");
        UiKit.stylePrimaryButton(nextBtn);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(40);
        nextBtn.setOnClickListener(v -> transitionOnboarding(onboardingStep1, onboardingStep2));
        root.addView(nextBtn, lp);
        return root;
    }

    private View buildStep2() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(40), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("학교 찾기");
        title.setTextColor(UiKit.TEXT_PRIMARY);
        title.setTextSize(24);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(title);

        LinearLayout searchCard = card();
        searchCard.addView(eyebrow("학교 검색"));
        
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(0, dp(12), 0, 0);
        
        EditText input = new EditText(this);
        input.setHint("학교 이름을 입력하세요");
        UiKit.styleInput(input);
        searchRow.addView(input, weightedWrap());
        
        Button btn = new Button(this);
        btn.setText("검색");
        UiKit.stylePrimaryButton(btn);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(8);
        searchRow.addView(btn, blp);
        searchCard.addView(searchRow);

        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(0, dp(12), 0, 0);
        searchCard.addView(results);
        root.addView(searchCard, cardLp());

        onboardingSelectedSchoolLabel = new TextView(this);
        onboardingSelectedSchoolLabel.setText("학교를 선택해 주세요.");
        UiKit.styleCaption(onboardingSelectedSchoolLabel);
        onboardingSelectedSchoolLabel.setPadding(dp(4), 0, 0, dp(24));
        root.addView(onboardingSelectedSchoolLabel);

        btn.setOnClickListener(v -> {
            String q = input.getText().toString().trim();
            if (q.isEmpty()) return;
            results.removeAllViews();
            results.addView(loadingRow("검색 중..."));
            ComciganApi.searchSchools(q, (schools, err) -> {
                results.removeAllViews();
                if (err != null || schools == null || schools.isEmpty()) {
                    results.addView(errorRow("학교를 찾지 못했어요."));
                    return;
                }
                for (ComciganApi.School s : schools) {
                    View row = schoolResultRow(s);
                    row.setOnClickListener(v2 -> {
                        pendingSchoolCode = s.code;
                        pendingSchoolName = s.name;
                        onboardingSelectedSchoolLabel.setText(s.name + " (선택됨)");
                        onboardingSelectedSchoolLabel.setTextColor(UiKit.ACCENT);
                        updateSchoolLocation(s.name);
                    });
                    results.addView(row);
                }
            });
        });

        Button nextBtn = new Button(this);
        nextBtn.setText("다음 단계");
        UiKit.stylePrimaryButton(nextBtn);
        nextBtn.setOnClickListener(v -> {
            if (pendingSchoolCode.isEmpty()) {
                Toast.makeText(this, "학교를 먼저 선택해 주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            transitionOnboarding(onboardingStep2, onboardingStep3);
        });
        root.addView(nextBtn, matchWrap());
        return root;
    }

    private View buildStep3() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(40), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("정보 설정");
        title.setTextColor(UiKit.TEXT_PRIMARY);
        title.setTextSize(24);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(title);

        onboardingIsTeacherModeCheck = new CheckBox(this);
        onboardingIsTeacherModeCheck.setText("선생님이신가요?");
        onboardingIsTeacherModeCheck.setTextColor(UiKit.TEXT_PRIMARY);
        onboardingIsTeacherModeCheck.setPadding(0, dp(16), 0, dp(16));
        root.addView(onboardingIsTeacherModeCheck);

        onboardingStudentFields = new LinearLayout(this);
        onboardingStudentFields.setOrientation(LinearLayout.VERTICAL);
        
        LinearLayout gradeRow = new LinearLayout(this);
        gradeRow.setOrientation(LinearLayout.HORIZONTAL);
        
        LinearLayout gCol = new LinearLayout(this);
        gCol.setOrientation(LinearLayout.VERTICAL);
        gCol.addView(fieldLabel("학년"));
        onboardingGradeAuto = makePickerInput(new String[]{"1학년", "2학년", "3학년"});
        gCol.addView(onboardingGradeAuto);
        
        LinearLayout cCol = new LinearLayout(this);
        cCol.setOrientation(LinearLayout.VERTICAL);
        cCol.addView(fieldLabel("반"));
        String[] cOpts = new String[20]; for(int i=0;i<20;i++) cOpts[i] = (i+1) + "반";
        onboardingClassAuto = makePickerInput(cOpts);
        cCol.addView(onboardingClassAuto);
        
        gradeRow.addView(gCol, weightedWrap());
        gradeRow.addView(cCol, weightedWrap());
        onboardingStudentFields.addView(gradeRow);
        root.addView(onboardingStudentFields);

        onboardingTeacherFields = new LinearLayout(this);
        onboardingTeacherFields.setOrientation(LinearLayout.VERTICAL);
        onboardingTeacherFields.setVisibility(View.GONE);
        onboardingTeacherFields.addView(fieldLabel("선생님 성함"));
        onboardingTeacherNameInput = new EditText(this);
        onboardingTeacherNameInput.setHint("예: 홍길동");
        UiKit.styleInput(onboardingTeacherNameInput);
        onboardingTeacherFields.addView(onboardingTeacherNameInput);
        root.addView(onboardingTeacherFields);

        onboardingIsTeacherModeCheck.setOnCheckedChangeListener((b, checked) -> {
            onboardingStudentFields.setVisibility(checked ? View.GONE : View.VISIBLE);
            onboardingTeacherFields.setVisibility(checked ? View.VISIBLE : View.GONE);
        });

        Button nextBtn = new Button(this);
        nextBtn.setText("다음 단계");
        UiKit.stylePrimaryButton(nextBtn);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(40);
        nextBtn.setOnClickListener(v -> {
            if (onboardingIsTeacherModeCheck.isChecked()) {
                if (onboardingTeacherNameInput.getText().toString().trim().isEmpty()) {
                    Toast.makeText(this, "성함을 입력해 주세요.", Toast.LENGTH_SHORT).show(); return;
                }
            } else {
                if (onboardingGradeAuto.getText().toString().isEmpty() || onboardingClassAuto.getText().toString().isEmpty()) {
                    Toast.makeText(this, "학년과 반을 선택해 주세요.", Toast.LENGTH_SHORT).show(); return;
                }
            }
            transitionOnboarding(onboardingStep3, onboardingStep4);
        });
        root.addView(nextBtn, lp);
        return root;
    }

    private View buildStep4() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(40), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("마지막 단계");
        title.setTextColor(UiKit.TEXT_PRIMARY);
        title.setTextSize(24);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(title);

        LinearLayout permCard = card();
        permCard.addView(onboardingLine("✅ 이용 약관 동의", "서비스 이용을 위한 최소한의 데이터 저장에 동의합니다."));
        permCard.addView(onboardingLine("🔔 알림 및 위치 권한", "시간표 알림 및 실내 지도 기능을 위해 권한 허용이 필요합니다."));
        root.addView(permCard, cardLp());

        Button finishBtn = new Button(this);
        finishBtn.setText("설정 완료");
        UiKit.stylePrimaryButton(finishBtn);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(40);
        finishBtn.setOnClickListener(v -> {
            // Save Settings
            prefs.setSchool(pendingSchoolCode, pendingSchoolName);
            prefs.setTeacherMode(onboardingIsTeacherModeCheck.isChecked());
            if (onboardingIsTeacherModeCheck.isChecked()) {
                prefs.setTeacherName(onboardingTeacherNameInput.getText().toString().trim());
            } else {
                try {
                    int g = Integer.parseInt(onboardingGradeAuto.getText().toString().replaceAll("[^0-9]", ""));
                    int c = Integer.parseInt(onboardingClassAuto.getText().toString().replaceAll("[^0-9]", ""));
                    prefs.setClass(g, c);
                } catch (Exception e) { prefs.setClass(1, 1); }
            }
            
            prefs.setOnboardingDone(true);
            prefs.setMappingConsentDone(true);
            requestAllPermissions(() -> {
                onboardingOverlay.animate().alpha(0).setDuration(400).withEndAction(() -> {
                    onboardingOverlay.setVisibility(View.GONE);
                    loadPrefsIntoUi();
                    startMappingServiceIfPermitted();
                    loadAllWeeks();
                    showPage(TAB_TIMETABLE);
                }).start();
            });
        });
        root.addView(finishBtn, lp);
        return root;
    }

    private void transitionOnboarding(View from, View to) {
        from.animate().alpha(0).translationX(-dp(50)).setDuration(300).withEndAction(() -> {
            from.setVisibility(View.GONE);
            to.setVisibility(View.VISIBLE);
            to.setAlpha(0f);
            to.setTranslationX(dp(50));
            to.animate().alpha(1f).translationX(0).setDuration(450)
                    .setInterpolator(new UiKit.SpringInterpolator(0.6)).start();
        }).start();
    }

    private LinearLayout onboardingLine(String head, String body) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, dp(12));
        TextView h = new TextView(this);
        h.setText(head);
        h.setTextColor(UiKit.ACCENT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextSize(13);
        col.addView(h);
        TextView b = new TextView(this);
        b.setText(body);
        b.setTextColor(UiKit.TEXT_SECONDARY);
        b.setTextSize(13);
        b.setPadding(0, dp(2), 0, 0);
        col.addView(b);
        return col;
    }

    // ==================== TIMETABLE PAGE ====================
    private LinearLayout buildTimetablePage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(dp(20), dp(20), dp(20), dp(4));

        LinearLayout headerClickable = new LinearLayout(this);
        headerClickable.setOrientation(LinearLayout.VERTICAL);
        headerClickable.setClickable(true);
        headerClickable.setFocusable(true);
        UiKit.attachBouncyPress(headerClickable);
        headerClickable.setOnClickListener(v -> showPage(TAB_SETTINGS));
        classHeaderLabel = new TextView(this);
        classHeaderLabel.setTextColor(UiKit.TEXT_PRIMARY);
        classHeaderLabel.setTextSize(20);
        classHeaderLabel.setTypeface(Typeface.DEFAULT_BOLD);
        classHeaderLabel.setText("학급을 설정해주세요 \u203a");
        headerClickable.addView(classHeaderLabel);
        teacherModeIndicator = new TextView(this);
        teacherModeIndicator.setTextColor(UiKit.ACCENT);
        teacherModeIndicator.setTextSize(12);
        teacherModeIndicator.setVisibility(View.GONE);
        headerClickable.addView(teacherModeIndicator);
        headerRow.addView(headerClickable, weightedWrap());

        Button historyBtn = new Button(this);
        historyBtn.setText("\u23f2 기록");
        historyBtn.setTextSize(11);
        UiKit.styleSecondaryButton(historyBtn);
        historyBtn.setOnClickListener(v -> showHistoryDialog());
        headerRow.addView(historyBtn);
        root.addView(headerRow);

        TextView hint = new TextView(this);
        hint.setText("길게 눌러 선생님 시간표 보기 (다시 탭하면 해제) · 탭 한 번은 일정 추가 · 좌우로 밀면 옆 반");
        hint.setTextColor(UiKit.TEXT_SECONDARY);
        hint.setTextSize(11);
        hint.setPadding(dp(20), 0, dp(20), dp(10));
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        LinearLayout scrollContent = new LinearLayout(this);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        scrollContent.setPadding(dp(20), 0, dp(20), dp(24));

        nowPanel = new LinearLayout(this);
        nowPanel.setOrientation(LinearLayout.VERTICAL);
        nowPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams nowLp = matchWrap();
        nowLp.bottomMargin = dp(12);
        scrollContent.addView(nowPanel, nowLp);

        prevWeekBtn = new Button(this);
        prevWeekBtn.setText("\u2191 이전 주 기록 보기");
        prevWeekBtn.setTextSize(12);
        UiKit.styleSecondaryButton(prevWeekBtn);
        prevWeekBtn.setOnClickListener(v -> loadPreviousArchivedWeek());
        LinearLayout.LayoutParams prevLp = matchWrap();
        prevLp.bottomMargin = dp(10);
        scrollContent.addView(prevWeekBtn, prevLp);

        weekSectionsContainer = new LinearLayout(this);
        weekSectionsContainer.setOrientation(LinearLayout.VERTICAL);
        scrollContent.addView(weekSectionsContainer);

        scroll.addView(scrollContent);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void tickNowPanel() {
        renderNowPanel();
        nowPanelHandler.postDelayed(this::tickNowPanel, 30_000);
    }

    private void renderNowPanel() {
        if (nowPanel == null) return;
        // INVISIBLE (not GONE) specifically for teacher-view mode: GONE
        // collapses the panel's height, which reflows everything below it
        // -- including the week grid the user may still be mid-gesture on
        // -- shifting it under their finger. INVISIBLE keeps the space
        // reserved so nothing else on the page moves. The other early-outs
        // below use GONE since they're not part of an interactive gesture.
        if (viewingTeacherName != null) {
            nowPanel.setVisibility(View.INVISIBLE);
            return;
        }
        if (prefs.schoolCode().isEmpty() || weekSections.isEmpty()) {
            nowPanel.setVisibility(View.GONE);
            return;
        }
        int dow = mondayBasedDow();
        if (dow == 0) { nowPanel.setVisibility(View.GONE); return; }

        Timetable tt = weekSections.get(0).tt;
        List<Timetable.PeriodEntry> today = tt.getDaySchedule(prefs.grade(), prefs.classNum(), dow);
        Calendar now = Calendar.getInstance();
        int nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        Timetable.PeriodEntry current = null;
        Timetable.PeriodEntry next = null;
        int nextStart = Integer.MAX_VALUE;
        int currentEnd = -1;
        Integer dayStart = null, dayEnd = null;
        for (Timetable.PeriodEntry e : today) {
            Integer[] range = parsePeriodRange(prefs.periodTime(e.period));
            if (range == null) continue;
            dayStart = dayStart == null ? range[0] : Math.min(dayStart, range[0]);
            dayEnd = dayEnd == null ? range[1] : Math.max(dayEnd, range[1]);
            if (nowMinutes >= range[0] && nowMinutes < range[1]) {
                current = e;
                currentEnd = range[1];
            } else if (range[0] > nowMinutes && range[0] < nextStart) {
                nextStart = range[0];
                next = e;
            }
        }

        if (current != null) {
            renderNowPanelInClass(current, currentEnd - nowMinutes);
        } else if (dayStart != null && nowMinutes >= dayStart && nowMinutes < dayEnd) {
            renderNowPanelBreak(next, next != null ? nextStart - nowMinutes : -1);
        } else {
            nowPanel.setVisibility(View.GONE);
        }
    }

    private String formatRemaining(int minutes) {
        if (minutes < 0) minutes = 0;
        if (minutes < 60) return minutes + "분";
        return (minutes / 60) + "시간 " + (minutes % 60) + "분";
    }

    private void renderNowPanelInClass(Timetable.PeriodEntry current, int remainingMinutes) {
        nowPanel.removeAllViews();
        nowPanel.setVisibility(View.VISIBLE);
        int subjColor = prefs.subjectColor(current.subject);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(prefs.solidTimetableColor() ? UiKit.darken(prefs.solidBaseColor(), 0.55f) : blend(subjColor, UiKit.SURFACE, 0.35f));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(2), UiKit.ACCENT);
        nowPanel.setBackground(bg);
        nowPanel.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView live = new TextView(this);
        live.setText("🔴 지금 " + current.period + "교시 · " + formatRemaining(remainingMinutes) + " 남음");
        live.setTextColor(UiKit.ACCENT);
        live.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        live.setTextSize(12);
        nowPanel.addView(live);

        TextView subj = new TextView(this);
        subj.setText(current.subject + (current.teacher.isEmpty() ? "" : "  ·  " + current.teacher));
        subj.setTextColor(Color.WHITE);
        subj.setTextSize(18);
        subj.setTypeface(Typeface.DEFAULT_BOLD);
        subj.setPadding(0, dp(4), 0, 0);
        nowPanel.addView(subj);

        String today2 = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new java.util.Date());
        Prefs.PersonalEvent ev = prefs.findPersonalEvent(today2, current.period);
        if (ev != null) {
            TextView evText = new TextView(this);
            evText.setText("📝 " + ev.text);
            evText.setTextColor(0xDDFFFFFF);
            evText.setTextSize(12);
            evText.setPadding(0, dp(4), 0, 0);
            nowPanel.addView(evText);
        }
    }

    private void renderNowPanelBreak(Timetable.PeriodEntry next, int remainingMinutes) {
        nowPanel.removeAllViews();
        nowPanel.setVisibility(View.VISIBLE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(UiKit.SURFACE_ALT);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), UiKit.BORDER);
        nowPanel.setBackground(bg);
        nowPanel.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView live = new TextView(this);
        live.setText(next != null ? "☕ 쉬는시간 · " + formatRemaining(remainingMinutes) + " 후 다음 수업" : "☕ 쉬는시간");
        live.setTextColor(UiKit.TEXT_SECONDARY);
        live.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        live.setTextSize(12);
        nowPanel.addView(live);

        TextView subj = new TextView(this);
        if (next != null) {
            subj.setText(next.period + "교시 " + next.subject + (next.teacher.isEmpty() ? "" : "  ·  " + next.teacher));
        } else {
            subj.setText("오늘 남은 수업이 없어요.");
        }
        subj.setTextColor(UiKit.TEXT_PRIMARY);
        subj.setTextSize(16);
        subj.setTypeface(Typeface.DEFAULT_BOLD);
        subj.setPadding(0, dp(4), 0, 0);
        nowPanel.addView(subj);
    }

    private Integer[] parsePeriodRange(String range) {
        try {
            String[] parts = range.split("-");
            String[] a = parts[0].trim().split(":");
            String[] b = parts[1].trim().split(":");
            return new Integer[]{Integer.parseInt(a[0]) * 60 + Integer.parseInt(a[1]),
                    Integer.parseInt(b[0]) * 60 + Integer.parseInt(b[1])};
        } catch (Exception e) { return null; }
    }

    private int mondayBasedDow() {
        int cal = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        switch (cal) {
            case Calendar.MONDAY: return 1;
            case Calendar.TUESDAY: return 2;
            case Calendar.WEDNESDAY: return 3;
            case Calendar.THURSDAY: return 4;
            case Calendar.FRIDAY: return 5;
            default: return 0;
        }
    }

    // Guards against a race that used to crash with IndexOutOfBoundsException:
    // if loadAllWeeks() is called again (e.g. quickly re-tapping the 시간표
    // tab) while a previous fetch chain is still in flight, the OLD chain's
    // callbacks would keep firing and add sections built against a
    // weekSections.size() that no longer matched weekSectionsContainer's
    // actual child count (the new call had already reset the container).
    // Every callback below checks its captured generation against the
    // current one and bails out silently if a newer load has superseded it.
    private void loadAllWeeks() {
        int myGeneration = ++loadGeneration;
        if (prefs.schoolCode().isEmpty()) {
            classHeaderLabel.setText("학급을 설정해주세요 \u203a");
            weekSectionsContainer.removeAllViews();
            weekSectionsContainer.addView(errorRow("설정 탭에서 학교와 반을 먼저 설정해주세요."));
            prevWeekBtn.setVisibility(View.GONE);
            return;
        }
        browseClassNum = prefs.classNum();
        // "선생님 모드" in Settings means the main 시간표 tab IS that
        // teacher's own schedule by default, not a student class's --
        // reuses the same locked teacher view long-pressing a cell enters.
        boolean teacherDefault = prefs.isTeacherMode() && !prefs.teacherName().trim().isEmpty();
        viewingTeacherName = teacherDefault ? prefs.teacherName().trim() : null;
        teacherViewLocked = teacherDefault;
        if (teacherDefault) {
            classHeaderLabel.setText(viewingTeacherName + " 선생님 시간표 \u203a");
            teacherModeIndicator.setText("\ud83d\udd12 " + viewingTeacherName + " 선생님 시간표 -- 탭하면 반 시간표로");
            teacherModeIndicator.setVisibility(View.VISIBLE);
        } else {
            classHeaderLabel.setText(prefs.grade() + "학년 " + browseClassNum + "반 \u203a");
            teacherModeIndicator.setVisibility(View.GONE);
        }
        weekSectionsContainer.removeAllViews();
        weekSections.clear();
        prevWeekBtn.setVisibility(prefs.archivedWeekDates().isEmpty() ? View.GONE : View.VISIBLE);
        weekSectionsContainer.addView(loadingRow("시간표를 불러오는 중..."));

        TimetableRepository.fetch(this, "1", (tt, offline, err) -> {
            if (myGeneration != loadGeneration) return;
            weekSectionsContainer.removeAllViews();
            if (tt == null) {
                weekSectionsContainer.addView(errorRow("불러오지 못했어요: " + (err != null ? err.getMessage() : "")));
                return;
            }
            List<Timetable.WeekOption> weeks = tt.weekOptions;
            if (weeks.isEmpty()) {
                weeks = new ArrayList<>();
                weeks.add(new Timetable.WeekOption("1", "이번 주"));
            }
            loadWeeksSequentially(myGeneration, weeks, 0, offline);
        });
    }

    private void loadWeeksSequentially(int generation, List<Timetable.WeekOption> weeks, int index, boolean firstOffline) {
        if (generation != loadGeneration) return;
        if (index >= weeks.size()) { renderNowPanel(); return; }
        Timetable.WeekOption w = weeks.get(index);
        TimetableRepository.fetch(this, w.code, (tt, offline, err) -> {
            if (generation != loadGeneration) return;
            if (tt != null) addWeekSection(tt, index, offline);
            loadWeeksSequentially(generation, weeks, index + 1, firstOffline);
        });
    }

    private void loadPreviousArchivedWeek() {
        if (weekSections.isEmpty()) return;
        String currentMonday = weekSections.get(0).tt.startDate;
        List<String> dates = prefs.archivedWeekDates();
        String target = null;
        for (String d : dates) {
            if (currentMonday.isEmpty() || d.compareTo(currentMonday) < 0) {
                if (target == null || d.compareTo(target) > 0) target = d;
            }
        }
        if (target == null) {
            Toast.makeText(this, "아직 저장된 지난 주 기록이 없어요. 몇 주 사용하면 쌓여요.", Toast.LENGTH_SHORT).show();
            return;
        }
        String json = prefs.archivedWeekJson(target);
        if (json == null) return;
        try {
            Timetable tt = Timetable.parse(new org.json.JSONObject(json));
            addWeekSectionAt(tt, "지난 주 (기록)", 0, false);
            prevWeekBtn.setVisibility(View.GONE);
        } catch (Exception ignored) {}
    }

    private void addWeekSection(Timetable tt, int index, boolean offline) {
        String chipLabel = index == 0 ? "이번주" : index == 1 ? "다음주" : (index + 1) + "주 후";
        addWeekSectionAt(tt, chipLabel, weekSections.size(), offline);
    }

    private void addWeekSectionAt(Timetable tt, String chipLabel, int insertIndex, boolean offline) {
        WeekSection ws = new WeekSection();
        ws.tt = tt;

        LinearLayout sectionCard = card();
        sectionCard.setLayoutParams(cardLp());

        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView chip = new TextView(this);
        chip.setText(chipLabel);
        chip.setTextColor(UiKit.ACCENT_TEXT);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setTextSize(11);
        chip.setBackground(UiKit.pillFilled(UiKit.ACCENT));
        chip.setPadding(dp(10), dp(3), dp(10), dp(3));
        labelRow.addView(chip);
        TextView dateRange = new TextView(this);
        dateRange.setText("  " + tt.startDate);
        dateRange.setTextColor(UiKit.TEXT_SECONDARY);
        dateRange.setTextSize(11);
        labelRow.addView(dateRange);
        LinearLayout.LayoutParams labelLp = matchWrap();
        labelLp.bottomMargin = dp(8);
        sectionCard.addView(labelRow, labelLp);

        if (offline) {
            long ts = prefs.cacheTimestamp(prefs.lastWeekCode());
            String timeStr = ts > 0 ? new SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(new java.util.Date(ts)) : "-";
            TextView banner = new TextView(this);
            banner.setText("\u26a0 오프라인 (최종: " + timeStr + ")");
            banner.setTextSize(11);
            banner.setTextColor(UiKit.CHANGED);
            banner.setPadding(dp(8), dp(2), dp(8), dp(8));
            sectionCard.addView(banner);
        }

        ws.grid = new TableLayout(this);
        ws.grid.setStretchAllColumns(false);
        for (int c = 1; c <= 5; c++) ws.grid.setColumnStretchable(c, true);
        sectionCard.addView(ws.grid, matchWrap());
        ws.card = sectionCard;
        attachCardSwipeGesture(sectionCard);
        attachGridTouch(ws);

        weekSections.add(insertIndex, ws);
        weekSectionsContainer.addView(sectionCard, insertIndex);
        UiKit.popIn(sectionCard);
        renderWeekSection(ws);
    }

    @SuppressWarnings("unchecked")
    private void renderWeekSection(WeekSection ws) {
        ws.grid.removeAllViews();
        Timetable tt = ws.tt;

        TableRow header = new TableRow(this);
        header.addView(gridHeaderCell(""));
        for (int d = 1; d <= 5; d++) header.addView(gridHeaderCell(DOW_SHORT[d]));
        ws.grid.addView(header);

        if (viewingTeacherName != null) {
            List<Timetable.TeacherPeriodEntry> all = tt.getTeacherWeek(viewingTeacherName);
            int maxPeriod = 1;
            for (Timetable.TeacherPeriodEntry e : all) maxPeriod = Math.max(maxPeriod, e.period);
            for (int p = 1; p <= maxPeriod; p++) {
                TableRow row = new TableRow(this);
                row.addView(periodNumCell(p));
                for (int d = 1; d <= 5; d++) {
                    Timetable.TeacherPeriodEntry match = null;
                    for (Timetable.TeacherPeriodEntry e : all) if (e.period == p && e.dayOfWeek == d) { match = e; break; }
                    row.addView(gridCellForTeacher(match));
                }
                ws.grid.addView(row);
            }
        } else {
            int cn = browseClassNum > 0 ? browseClassNum : prefs.classNum();
            List<Timetable.PeriodEntry>[] byDay = new List[6];
            int maxPeriod = 1;
            for (int d = 1; d <= 5; d++) {
                byDay[d] = tt.getDaySchedule(prefs.grade(), cn, d);
                for (Timetable.PeriodEntry e : byDay[d]) maxPeriod = Math.max(maxPeriod, e.period);
            }
            for (int p = 1; p <= maxPeriod; p++) {
                TableRow row = new TableRow(this);
                row.addView(periodNumCell(p));
                for (int d = 1; d <= 5; d++) {
                    Timetable.PeriodEntry match = null;
                    for (Timetable.PeriodEntry e : byDay[d]) if (e.period == p) { match = e; break; }
                    String date = dateForDay(tt, d);
                    row.addView(gridCellForClass(match, date, p));
                }
                ws.grid.addView(row);
            }
        }
    }

    private void rerenderAllSections() {
        for (WeekSection ws : weekSections) renderWeekSection(ws);
        renderNowPanel();
    }

    private String dateForDay(Timetable tt, int dayOfWeek) {
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA);
            java.util.Date monday = fmt.parse(tt.startDate);
            Calendar cal = Calendar.getInstance();
            cal.setTime(monday);
            cal.add(Calendar.DAY_OF_MONTH, dayOfWeek - 1);
            return fmt.format(cal.getTime());
        } catch (Exception e) {
            return "";
        }
    }

    private TextView periodNumCell(int p) {
        TextView periodCell = new TextView(this);
        periodCell.setText(String.valueOf(p));
        periodCell.setTextColor(UiKit.TEXT_SECONDARY);
        periodCell.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        periodCell.setTextSize(11);
        periodCell.setGravity(Gravity.CENTER);
        periodCell.setWidth(dp(20));
        return periodCell;
    }

    private TextView gridHeaderCell(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(UiKit.TEXT_SECONDARY);
        t.setTextSize(11);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, 0, dp(6));
        TableRow.LayoutParams lp = text.isEmpty()
                ? new TableRow.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT)
                : new TableRow.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t.setLayoutParams(lp);
        return t;
    }

    // ---------- class-view cell: tap = event dialog, long-press+slide-up = lock teacher view ----------
    private View gridCellForClass(Timetable.PeriodEntry e, String date, int period) {
        LinearLayout cell = emptyCell();
        boolean hasEvent = !date.isEmpty() && prefs.findPersonalEvent(date, period) != null;
        if (e != null && !e.subject.isEmpty()) {
            fillCell(cell, e.subject, e.teacher, e.changed);
        }
        if (hasEvent) {
            TextView dot = new TextView(this);
            dot.setText("\ud83d\udcdd");
            dot.setTextSize(9);
            dot.setGravity(Gravity.CENTER);
            cell.addView(dot);
        }
        UiKit.attachBouncyPress(cell);
        return cell;
    }

    // Long-press (~420ms hold, no drag needed to start it) swaps THIS grid
    // live to the teacher's schedule while the finger is still down;
    // dragging up locks it in, releasing without dragging up reverts back
    // to the class view. Tapping any cell while a teacher's schedule is
    // showing unlocks it.
    //
    // The listener lives on ws.grid itself (not on individual cells) so
    // that swapping the grid's contents mid-gesture doesn't cancel the
    // touch: rerenderAllSections() only replaces ws.grid's CHILDREN
    // (removeAllViews + re-add), never ws.grid itself, so the view that
    // actually owns this ongoing gesture is never detached. Attaching the
    // gesture to individual cells (the earlier approach) broke this,
    // because rebuilding necessarily destroyed whichever cell the finger
    // was resting on, and Android cancels a touch stream when its target
    // view is detached -- that's what caused the old "flashes then
    // immediately reverts" bug. Cells still get UiKit.attachBouncyPress
    // for tap feedback, but that listener never consumes the event (see
    // its own comment), so everything always bubbles up here.
    private void attachGridTouch(WeekSection ws) {
        final float[] startY = {0};
        final boolean[] longPressFired = {false};
        final boolean[] moved = {false};
        final int[] downPeriod = {-1};
        final int[] downDay = {-1};
        final View[] touchedCell = {null};
        Handler handler = new Handler();
        final Runnable[] pending = new Runnable[1];

        ws.grid.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    startY[0] = event.getRawY();
                    longPressFired[0] = false;
                    moved[0] = false;
                    int[] hit = hitTestGrid(ws.grid, event.getX(), event.getY());
                    downPeriod[0] = hit[0];
                    downDay[0] = hit[1];
                    touchedCell[0] = (hit[0] > 0 && hit[1] > 0)
                            ? ((TableRow) ws.grid.getChildAt(hit[0])).getChildAt(hit[1]) : null;
                    if (touchedCell[0] != null) {
                        touchedCell[0].animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start();
                    }
                    if (viewingTeacherName == null && downPeriod[0] > 0 && downDay[0] > 0) {
                        Timetable.PeriodEntry entry = findClassEntry(ws, downPeriod[0], downDay[0]);
                        if (entry != null && !entry.teacher.isEmpty()) {
                            String teacherName = entry.teacher;
                            pending[0] = () -> {
                                longPressFired[0] = true;
                                enterTeacherView(teacherName);
                            };
                            handler.postDelayed(pending[0], 420);
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    // Only guards against an in-progress *page scroll*
                    // being mistaken for a long press before it fires --
                    // once longPressFired is true the teacher view is
                    // already shown and locked (see enterTeacherView()),
                    // so hand movement afterward does nothing at all
                    // instead of silently reverting it (that used to
                    // require swiping up 70dp to "lock" first, and any
                    // ordinary hand tremor while holding, short of that,
                    // reverted the view the moment the finger lifted).
                    float dy = startY[0] - event.getRawY();
                    if (!longPressFired[0] && Math.abs(dy) > dp(18)) {
                        moved[0] = true;
                        if (pending[0] != null) handler.removeCallbacks(pending[0]);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    if (touchedCell[0] != null) {
                        touchedCell[0].animate().scaleX(1f).scaleY(1f).setDuration(100)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    }
                    if (pending[0] != null) handler.removeCallbacks(pending[0]);
                    if (!longPressFired[0] && !moved[0]) {
                        if (viewingTeacherName != null) {
                            exitTeacherView();
                        } else if (downPeriod[0] > 0 && downDay[0] > 0) {
                            String date = dateForDay(ws.tt, downDay[0]);
                            if (!date.isEmpty()) {
                                openEventDialog(date, downPeriod[0], findClassEntry(ws, downPeriod[0], downDay[0]));
                            }
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: {
                    if (touchedCell[0] != null) {
                        touchedCell[0].animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    }
                    if (pending[0] != null) handler.removeCallbacks(pending[0]);
                    return true;
                }
            }
            return false;
        });
    }

    // Maps a raw touch point (in ws.grid's own coordinate space) to
    // {period, dayOfWeek} by walking the already-laid-out rows/cells --
    // valid because renderWeekSection always builds row child index i =
    // period i, and within a row, cell child index j = day-of-week j (both
    // header/period-number slots at index 0). Returns {-1, -1} on a miss.
    private int[] hitTestGrid(TableLayout grid, float x, float y) {
        for (int i = 1; i < grid.getChildCount(); i++) {
            View rowView = grid.getChildAt(i);
            if (y < rowView.getTop() || y >= rowView.getBottom()) continue;
            if (!(rowView instanceof TableRow)) break;
            TableRow row = (TableRow) rowView;
            for (int j = 1; j < row.getChildCount(); j++) {
                View cell = row.getChildAt(j);
                // cell.getLeft()/getRight() are relative to the row, not the
                // grid, so translate by the row's own offset within the grid.
                if (x >= row.getLeft() + cell.getLeft() && x < row.getLeft() + cell.getRight()) {
                    return new int[]{i, j};
                }
            }
            break;
        }
        return new int[]{-1, -1};
    }

    private Timetable.PeriodEntry findClassEntry(WeekSection ws, int period, int dayOfWeek) {
        int cn = browseClassNum > 0 ? browseClassNum : prefs.classNum();
        for (Timetable.PeriodEntry e : ws.tt.getDaySchedule(prefs.grade(), cn, dayOfWeek)) {
            if (e.period == period) return e;
        }
        return null;
    }

    private View gridCellForTeacher(Timetable.TeacherPeriodEntry e) {
        LinearLayout cell = emptyCell();
        if (e != null) fillCell(cell, e.subject, e.grade + "-" + e.classNum, e.changed);
        UiKit.attachBouncyPress(cell);
        return cell;
    }

    private LinearLayout emptyCell() {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        TableRow.LayoutParams lp = new TableRow.LayoutParams(0, dp(64), 1f);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        cell.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(UiKit.SURFACE_ALT);
        bg.setCornerRadius(dp(4));
        bg.setStroke(Math.max(1, dp(1)), UiKit.BORDER);
        cell.setBackground(bg);
        return cell;
    }

    private void fillCell(LinearLayout cell, String subject, String subLabel, boolean changed) {
        GradientDrawable bg = new GradientDrawable();
        if (prefs.solidTimetableColor()) {
            int base = prefs.solidBaseColor();
            bg.setColor(changed ? UiKit.darken(base, 0.6f) : base);
        } else {
            bg.setColor(blend(prefs.subjectColor(subject), UiKit.SURFACE, 0.72f));
        }
        bg.setCornerRadius(dp(4));
        cell.setBackground(bg);
        cell.setPadding(dp(4), dp(5), dp(4), dp(5));

        TextView subjectView = new TextView(this);
        subjectView.setText(subject);
        subjectView.setTextColor(Color.WHITE);
        subjectView.setTextSize(13);
        subjectView.setTypeface(Typeface.DEFAULT_BOLD);
        subjectView.setGravity(Gravity.CENTER);
        subjectView.setMaxLines(2);
        cell.addView(subjectView);

        if (subLabel != null && !subLabel.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(subLabel);
            sub.setTextColor(0xDDFFFFFF);
            sub.setTextSize(10);
            sub.setGravity(Gravity.CENTER);
            cell.addView(sub);
        }

        if (changed) {
            TextView changedChip = new TextView(this);
            changedChip.setText("변경");
            changedChip.setTextColor(Color.WHITE);
            changedChip.setTextSize(9);
            changedChip.setTypeface(Typeface.DEFAULT_BOLD);
            changedChip.setBackground(UiKit.pillFilled(UiKit.CHANGED));
            changedChip.setPadding(dp(6), dp(1), dp(6), dp(1));
            changedChip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(1);
            clp.gravity = Gravity.CENTER_HORIZONTAL;
            cell.addView(changedChip, clp);
        }
    }

    private int blend(int fg, int bg, float fgWeight) {
        int r1 = (fg >> 16) & 0xFF, g1 = (fg >> 8) & 0xFF, b1 = fg & 0xFF;
        int r2 = (bg >> 16) & 0xFF, g2 = (bg >> 8) & 0xFF, b2 = bg & 0xFF;
        int r = (int) (r1 * fgWeight + r2 * (1 - fgWeight));
        int g = (int) (g1 * fgWeight + g2 * (1 - fgWeight));
        int b = (int) (b1 * fgWeight + b2 * (1 - fgWeight));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // Long-press fired: swap this AND every other section's grid to the
    // teacher's schedule immediately and lock it there (see
    // attachGridTouch for why this is safe -- the touch listener lives
    // on ws.grid, which rerenderAllSections() never detaches, only its
    // children). Simple on purpose: no separate "hold to preview, then
    // swipe up to lock" step -- that made ordinary hand movement while
    // holding revert the view the moment the finger lifted.
    private void enterTeacherView(String teacherName) {
        viewingTeacherName = teacherName;
        teacherViewLocked = true;
        teacherModeIndicator.setText("\ud83d\udd12 " + teacherName + " 선생님 시간표 -- 탭하면 반 시간표로");
        teacherModeIndicator.setVisibility(View.VISIBLE);
        rerenderAllSections();
    }

    private void exitTeacherView() {
        if (viewingTeacherName == null) return;
        viewingTeacherName = null;
        teacherViewLocked = false;
        teacherModeIndicator.setVisibility(View.GONE);
        classHeaderLabel.setText(prefs.grade() + "학년 " + browseClassNum + "반 \u203a");
        rerenderAllSections();
    }

    private void attachCardSwipeGesture(View cardView) {
        final float[] startX = {0};
        final float[] startY = {0};
        cardView.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = event.getX();
                    startY[0] = event.getY();
                    return false;
                case MotionEvent.ACTION_UP:
                    float totalDx = event.getX() - startX[0];
                    float totalDy = event.getY() - startY[0];
                    if (Math.abs(totalDx) > dp(70) && Math.abs(totalDx) > Math.abs(totalDy) * 1.5f) {
                        if (viewingTeacherName != null) {
                            exitTeacherView();
                        } else {
                            int cn = browseClassNum > 0 ? browseClassNum : prefs.classNum();
                            cn = totalDx < 0 ? Math.min(20, cn + 1) : Math.max(1, cn - 1);
                            browseClassNum = cn;
                            boolean isOwn = cn == prefs.classNum();
                            classHeaderLabel.setText(prefs.grade() + "학년 " + cn + "반" + (isOwn ? "" : " (미리보기)") + " \u203a");
                            UiKit.slideAndFadeIn(cardView, totalDx < 0 ? dp(80) : -dp(80));
                            rerenderAllSections();
                        }
                    }
                    return false;
            }
            return false;
        });
    }

    // ---------- event dialog ----------
    private void openEventDialog(String date, int period, Timetable.PeriodEntry entry) {
        Prefs.PersonalEvent existing = prefs.findPersonalEvent(date, period);
        Dialog dialog = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiKit.card());
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText(date + "  " + period + "교시" + (entry != null && !entry.subject.isEmpty() ? " \u00b7 " + entry.subject : ""));
        title.setTextColor(UiKit.TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        EditText input = new EditText(this);
        UiKit.styleInput(input);
        input.setHint("예: 수행평가, 준비물 등");
        if (existing != null) input.setText(existing.text);
        LinearLayout.LayoutParams inputLp = matchWrap();
        inputLp.topMargin = dp(12);
        root.addView(input, inputLp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(12), 0, 0);

        if (existing != null) {
            Button delBtn = new Button(this);
            delBtn.setText("삭제");
            UiKit.styleSecondaryButton(delBtn);
            delBtn.setOnClickListener(v -> {
                prefs.deletePersonalEvent(date, period);
                dialog.dismiss();
                rerenderAllSections();
            });
            LinearLayout.LayoutParams delLp = weightedWrap();
            delLp.rightMargin = dp(8);
            btnRow.addView(delBtn, delLp);
        }

        Button saveBtn = new Button(this);
        saveBtn.setText(existing != null ? "수정" : "추가");
        UiKit.stylePrimaryButton(saveBtn);
        saveBtn.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) { dialog.dismiss(); return; }
            if (existing != null) {
                prefs.editPersonalEvent(date, period, text);
                dialog.dismiss();
                rerenderAllSections();
            } else {
                Prefs.AddEventResult result = prefs.addPersonalEvent(date, period, text);
                if (result == Prefs.AddEventResult.OK) {
                    dialog.dismiss();
                    rerenderAllSections();
                } else if (result == Prefs.AddEventResult.RATE_LIMITED) {
                    Toast.makeText(this, "너무 빨리 여러 개를 추가하고 있어요. 잠시 후 다시 시도해주세요.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "이미 이 시간에 일정이 있어요. 수정만 가능해요.", Toast.LENGTH_SHORT).show();
                }
            }
        });
        btnRow.addView(saveBtn, weightedWrap());
        root.addView(btnRow);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        dialog.show();
    }

    // ---------- version history dialog ----------
    private void showHistoryDialog() {
        List<Prefs.HistoryEntry> hist = prefs.changeHistory();
        Dialog dialog = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiKit.card());
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("변동 기록");
        title.setTextColor(UiKit.TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(16);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        if (hist.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("아직 기록된 변동이 없어요.");
            UiKit.styleCaption(empty);
            list.addView(empty);
        } else {
            for (int i = 0; i < hist.size(); i++) {
                View row = historyRow(hist.get(i));
                list.addView(row);
                row.setTranslationX(dp(30));
                row.setAlpha(0f);
                row.animate().alpha(1f).translationX(0f).setStartDelay(i * 40L).setDuration(160)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            }
        }
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(400)));

        dialog.setContentView(root);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        dialog.show();
    }

    private View historyRow(Prefs.HistoryEntry h) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView meta = new TextView(this);
        meta.setText(h.timestamp + "  " + h.dayLabel + "요일 " + h.period + "교시");
        meta.setTextColor(UiKit.TEXT_SECONDARY);
        meta.setTextSize(11);
        row.addView(meta);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        chipRow.setPadding(0, dp(4), 0, 0);
        chipRow.addView(chip(h.oldSubject, UiKit.SURFACE_ALT));
        TextView arrow = new TextView(this);
        arrow.setText("  \u2192  ");
        arrow.setTextColor(UiKit.ACCENT);
        arrow.setTypeface(Typeface.DEFAULT_BOLD);
        chipRow.addView(arrow);
        chipRow.addView(chip(h.newSubject, prefs.subjectColor(h.newSubject)));
        row.addView(chipRow);
        return row;
    }

    private TextView chip(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(blend(color, UiKit.SURFACE, 0.8f));
        bg.setCornerRadius(dp(999));
        t.setBackground(bg);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        return t;
    }

    // ==================== MEAL PAGE ====================
    private LinearLayout buildMealPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));

        TextView title = new TextView(this);
        title.setText("급식");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiKit.TEXT_PRIMARY);
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title);

        LinearLayout mealCard = card();
        mealStatusText = new TextView(this);
        UiKit.styleCaption(mealStatusText);
        mealStatusText.setPadding(0, dp(4), 0, dp(4));
        mealCard.addView(mealStatusText);
        mealContent = new LinearLayout(this);
        mealContent.setOrientation(LinearLayout.VERTICAL);
        mealCard.addView(mealContent);
        root.addView(mealCard, cardLp());

        TextView note = new TextView(this);
        note.setText("NEIS API 키는 설정 탭 하단에서 등록할 수 있어요.");
        UiKit.styleCaption(note);
        root.addView(note);

        return root;
    }

    private void refreshMeal() {
        if (mealStatusText == null) return;
        String key = prefs.neisApiKey();
        mealContent.removeAllViews();
        if (key.isEmpty()) {
            mealStatusText.setText("설정 탭에서 NEIS API 키를 입력하면 급식 정보를 볼 수 있어요.");
            return;
        }
        if (prefs.schoolCode().isEmpty()) {
            mealStatusText.setText("설정 탭에서 학교를 먼저 설정해주세요.");
            return;
        }
        mealStatusText.setText("연결하는 중...");
        NeisApi.searchSchool(key, prefs.schoolName(), (matches, err) -> {
            if (err != null || matches == null || matches.isEmpty()) {
                mealStatusText.setText("학교를 NEIS에서 찾지 못했어요.");
                return;
            }
            NeisApi.SchoolMatch m = matches.get(0);
            prefs.setNeisSchool(m.officeCode, m.schoolCode);
            String today = new SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(new java.util.Date());
            NeisApi.fetchMeal(key, m.officeCode, m.schoolCode, today, (meals, mealErr) -> {
                if (mealErr != null) { mealStatusText.setText("급식 정보를 가져오지 못했어요: " + mealErr.getMessage()); return; }
                if (meals.isEmpty()) { mealStatusText.setText("오늘은 등록된 급식 정보가 없어요."); return; }
                mealStatusText.setText("");
                for (String meal : meals) {
                    TextView t = new TextView(this);
                    t.setText(meal);
                    UiKit.styleBody(t);
                    t.setPadding(0, dp(4), 0, dp(4));
                    mealContent.addView(t);
                    UiKit.popIn(t);
                }
            });
        });
    }

    // ==================== SETTINGS PAGE (accordion, decluttered) ====================
    private LinearLayout accSchool, accTheme, accNotif, accMeal;
    private Button accSchoolBtn, accThemeBtn, accNotifBtn, accMealBtn;

    private LinearLayout buildSettingsPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));

        TextView title = new TextView(this);
        title.setText("설정");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiKit.TEXT_PRIMARY);
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        accSchool = buildSchoolSection();
        accTheme = buildThemeSection();
        accNotif = buildNotifSection();
        accMeal = buildMealSection();

        accSchoolBtn = accordionHeader("\ud83c\udfeb  학교 / 학급", accSchool);
        accThemeBtn = accordionHeader("\ud83c\udfa8  테마 (과목 색상)", accTheme);
        accNotifBtn = accordionHeader("\ud83d\udd14  알림", accNotif);
        accMealBtn = accordionHeader("\ud83c\udf7d  급식 연동", accMeal);

        list.addView(accSchoolBtn); list.addView(accSchool);
        list.addView(accThemeBtn); list.addView(accTheme);
        list.addView(accNotifBtn); list.addView(accNotif);
        list.addView(accMealBtn); list.addView(accMeal);

        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    // Own top-level tab (TAB_MAP) now, not a Settings accordion entry --
    // the live sensor readout / 3D path view need real screen space and
    // don't belong buried behind a settings toggle a user has to remember
    // to open every time.
    private LinearLayout buildMapPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));

        TextView title = new TextView(this);
        title.setText("실내 지도");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiKit.TEXT_PRIMARY);
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(buildMappingSection());
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private Button accordionHeader(String text, LinearLayout content) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        UiKit.styleSecondaryButton(b);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(4);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            boolean opening = content.getVisibility() != View.VISIBLE;
            for (LinearLayout other : new LinearLayout[]{accSchool, accTheme, accNotif, accMeal}) {
                if (other != null) other.setVisibility(View.GONE);
            }
            if (opening) {
                content.setVisibility(View.VISIBLE);
                UiKit.popIn(content);
            }
        });
        return b;
    }

    // Keeps the live sensor readout / 3D gizmo / raw-data graphs updating
    // at MAPPING_TICK_MS, but only while the 지도 tab is actually open --
    // showPage() clears pending callbacks on every tab switch so it's
    // never ticking in the background for no reason.
    // The DB-backed bits (status/counts/path drawing) only need a fraction
    // of that rate: querying SQLite on the main thread every ~300ms would
    // risk visible jank, so those refresh every MAPPING_SLOW_TICKS ticks
    // instead, while everything else here reads straight off the live
    // MappingCollector's in-memory fields.
    private static final long MAPPING_TICK_MS = 300;
    private static final int MAPPING_SLOW_TICKS = 7; // ~every 2.1s

    private void startMappingTick() {
        if (mappingTick == null) {
            mappingTick = new Runnable() {
                int tickCount = 0;
                @Override
                public void run() {
                    if (tickCount % MAPPING_SLOW_TICKS == 0) {
                        refreshMappingStatus();
                    } else {
                        updateMappingLiveViews();
                    }
                    tickCount++;
                    mappingTickHandler.postDelayed(this, MAPPING_TICK_MS);
                }
            };
        }
        mappingTickHandler.removeCallbacks(mappingTick);
        mappingTickHandler.post(mappingTick);
    }

    private LinearLayout buildSchoolSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setVisibility(View.GONE);
        section.setPadding(0, dp(4), 0, dp(16));

        LinearLayout searchCard = card();
        searchCard.addView(eyebrow("학교 검색"));
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(0, dp(6), 0, 0);
        searchInput = new EditText(this);
        searchInput.setHint("학교 이름");
        UiKit.styleInput(searchInput);
        LinearLayout.LayoutParams searchInputLp = weightedWrap();
        searchInputLp.rightMargin = dp(8);
        searchRow.addView(searchInput, searchInputLp);
        Button searchBtn = new Button(this);
        searchBtn.setText("검색");
        UiKit.stylePrimaryButton(searchBtn);
        searchBtn.setOnClickListener(v -> doSearch());
        searchRow.addView(searchBtn);
        searchCard.addView(searchRow);
        searchResults = new LinearLayout(this);
        searchResults.setOrientation(LinearLayout.VERTICAL);
        searchResults.setPadding(0, dp(10), 0, 0);
        searchCard.addView(searchResults);
        section.addView(searchCard, cardLp());

        LinearLayout selectedCard = card();
        selectedCard.addView(eyebrow("선택된 학교"));
        selectedSchoolLabel = new TextView(this);
        UiKit.styleBody(selectedSchoolLabel);
        selectedSchoolLabel.setPadding(0, dp(4), 0, 0);
        selectedSchoolLabel.setText("아직 없음");
        selectedSchoolLabel.setTextColor(UiKit.TEXT_SECONDARY);
        selectedCard.addView(selectedSchoolLabel);
        section.addView(selectedCard, cardLp());

        LinearLayout fieldsCard = card();
        fieldsCard.addView(eyebrow("신분 설정"));
        
        teacherModeCheck = new CheckBox(this);
        teacherModeCheck.setText("선생님 모드 사용");
        teacherModeCheck.setTextColor(UiKit.TEXT_PRIMARY);
        fieldsCard.addView(teacherModeCheck);
        
        studentSettingsBox = new LinearLayout(this);
        studentSettingsBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout gradeRow = new LinearLayout(this);
        gradeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout gradeCol = new LinearLayout(this);
        gradeCol.setOrientation(LinearLayout.VERTICAL);
        gradeCol.addView(fieldLabel("학년"));
        gradeAuto = makePickerInput(new String[]{"1학년", "2학년", "3학년", "4학년", "5학년", "6학년"});
        gradeCol.addView(gradeAuto);
        LinearLayout classCol = new LinearLayout(this);
        classCol.setOrientation(LinearLayout.VERTICAL);
        classCol.addView(fieldLabel("반"));
        String[] classOptions = new String[20];
        for (int i = 0; i < 20; i++) classOptions[i] = String.valueOf(i + 1) + "반";
        classAuto = makePickerInput(classOptions);
        classCol.addView(classAuto);
        gradeRow.addView(gradeCol, weightedWrap());
        gradeRow.addView(classCol, weightedWrap());
        studentSettingsBox.addView(gradeRow);
        fieldsCard.addView(studentSettingsBox);
        
        teacherSettingsBox = new LinearLayout(this);
        teacherSettingsBox.setOrientation(LinearLayout.VERTICAL);
        teacherSettingsBox.setVisibility(View.GONE);
        teacherSettingsBox.addView(fieldLabel("선생님 성함"));
        teacherNameInput = new EditText(this);
        teacherNameInput.setHint("홍길동");
        UiKit.styleInput(teacherNameInput);
        teacherSettingsBox.addView(teacherNameInput);
        fieldsCard.addView(teacherSettingsBox);
        
        teacherModeCheck.setOnCheckedChangeListener((b, checked) -> {
            studentSettingsBox.setVisibility(checked ? View.GONE : View.VISIBLE);
            teacherSettingsBox.setVisibility(checked ? View.VISIBLE : View.GONE);
        });
        
        section.addView(fieldsCard, cardLp());

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button saveBtn = new Button(this);
        saveBtn.setText("적용하기");
        UiKit.stylePrimaryButton(saveBtn);
        saveBtn.setOnClickListener(v -> saveSetup());
        btnRow.addView(saveBtn, weightedWrap());
        Button saveAsBtn = new Button(this);
        saveAsBtn.setText("학급으로 저장");
        UiKit.styleSecondaryButton(saveAsBtn);
        LinearLayout.LayoutParams saveAsLp = weightedWrap();
        saveAsLp.leftMargin = dp(8);
        saveAsBtn.setOnClickListener(v -> saveCurrentAsNamedClass());
        btnRow.addView(saveAsBtn, saveAsLp);
        LinearLayout.LayoutParams btnRowLp = matchWrap();
        btnRowLp.bottomMargin = dp(12);
        section.addView(btnRow, btnRowLp);

        LinearLayout savedCard = card();
        savedCard.addView(eyebrow("저장된 학급"));
        savedClassesList = new LinearLayout(this);
        savedClassesList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams savedLp = matchWrap();
        savedLp.topMargin = dp(6);
        savedCard.addView(savedClassesList, savedLp);
        section.addView(savedCard, cardLp());

        return section;
    }

    private LinearLayout buildThemeSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setVisibility(View.GONE);
        section.setPadding(0, dp(4), 0, dp(16));

        LinearLayout displayCard = card();
        displayCard.addView(eyebrow("표시 방식"));
        solidColorCheck = styledCheckbox("단색으로 표시 (모든 과목 같은 색, 변동은 진한 색)");
        LinearLayout.LayoutParams solidLp = matchWrap();
        solidLp.topMargin = dp(4);
        displayCard.addView(solidColorCheck, solidLp);

        LinearLayout baseColorRow = new LinearLayout(this);
        baseColorRow.setOrientation(LinearLayout.HORIZONTAL);
        baseColorRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams baseColorRowLp = matchWrap();
        baseColorRowLp.topMargin = dp(8);
        TextView baseColorLabel = new TextView(this);
        baseColorLabel.setText("단색 모드 기준 색");
        UiKit.styleCaption(baseColorLabel);
        baseColorRow.addView(baseColorLabel, weightedWrap());
        View baseColorSwatch = new View(this);
        GradientDrawable baseColorSwatchBg = new GradientDrawable();
        baseColorSwatchBg.setColor(prefs.solidBaseColor());
        baseColorSwatchBg.setCornerRadius(dp(8));
        baseColorSwatch.setBackground(baseColorSwatchBg);
        LinearLayout.LayoutParams baseColorSwatchLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        baseColorSwatch.setLayoutParams(baseColorSwatchLp);
        baseColorSwatch.setClickable(true);
        baseColorSwatch.setFocusable(true);
        UiKit.attachBouncyPress(baseColorSwatch);
        baseColorSwatch.setOnClickListener(v -> UiKit.showColorPicker(this, color -> {
            prefs.setSolidBaseColor(color);
            baseColorSwatchBg.setColor(color);
            rerenderAllSections();
        }));
        baseColorRow.addView(baseColorSwatch);
        displayCard.addView(baseColorRow, baseColorRowLp);

        solidColorCheck.setOnCheckedChangeListener((b, checked) -> {
            prefs.setSolidTimetableColor(checked);
            rerenderAllSections();
        });
        section.addView(displayCard, cardLp());

        LinearLayout colorsCard = card();
        colorsCard.addView(eyebrow("과목별 색상"));
        TextView colorsHint = new TextView(this);
        colorsHint.setText("단색 모드가 꺼져 있을 때 사용돼요.");
        UiKit.styleCaption(colorsHint);
        colorsHint.setPadding(0, dp(2), 0, 0);
        colorsCard.addView(colorsHint);
        colorsList = new LinearLayout(this);
        colorsList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = matchWrap();
        clp.topMargin = dp(6);
        colorsCard.addView(colorsList, clp);
        section.addView(colorsCard, cardLp());
        return section;
    }

    private LinearLayout buildNotifSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setVisibility(View.GONE);
        section.setPadding(0, dp(4), 0, dp(16));

        LinearLayout togglesCard = card();
        notifyChangeCheck = styledCheckbox("시간표 변동 알림");
        togglesCard.addView(notifyChangeCheck);
        notifyPeriodCheck = styledCheckbox("쉬는시간마다 다음 수업 알림");
        togglesCard.addView(notifyPeriodCheck);
        notifyMorningCheck = styledCheckbox("아침 시간표 알림");
        togglesCard.addView(notifyMorningCheck);
        liveNotifyCheck = styledCheckbox("Live Notify");
        togglesCard.addView(liveNotifyCheck);
        android.widget.CompoundButton.OnCheckedChangeListener autoSave = (b, checked) -> saveNotifySettings();
        notifyChangeCheck.setOnCheckedChangeListener(autoSave);
        notifyPeriodCheck.setOnCheckedChangeListener(autoSave);
        notifyMorningCheck.setOnCheckedChangeListener(autoSave);
        liveNotifyCheck.setOnCheckedChangeListener(autoSave);
        section.addView(togglesCard, cardLp());

        LinearLayout morningCard = card();
        morningCard.addView(eyebrow("아침 알림 시각"));
        morningTimeInput = new EditText(this);
        morningTimeInput.setHint("07:30");
        UiKit.styleInput(morningTimeInput);
        LinearLayout.LayoutParams morningLp = matchWrap();
        morningLp.topMargin = dp(6);
        morningCard.addView(morningTimeInput, morningLp);
        Button morningSave = new Button(this);
        morningSave.setText("저장");
        morningSave.setTextSize(11);
        UiKit.styleSecondaryButton(morningSave);
        morningSave.setOnClickListener(v -> { saveNotifySettings(); Toast.makeText(this, "저장했어요.", Toast.LENGTH_SHORT).show(); });
        LinearLayout.LayoutParams msLp = matchWrap();
        msLp.topMargin = dp(6);
        morningCard.addView(morningSave, msLp);
        section.addView(morningCard, cardLp());

        LinearLayout periodsCard = card();
        periodsCard.addView(eyebrow("교시별 시간"));
        TextView pHint = new TextView(this);
        pHint.setText("형식: 09:00-09:50");
        UiKit.styleCaption(pHint);
        pHint.setPadding(0, dp(2), 0, dp(8));
        periodsCard.addView(pHint);
        for (int i = 0; i < 8; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = matchWrap();
            rowLp.bottomMargin = dp(6);
            row.setLayoutParams(rowLp);
            TextView label = new TextView(this);
            label.setText((i + 1) + "교시");
            label.setTextColor(UiKit.TEXT_SECONDARY);
            label.setWidth(dp(48));
            EditText input = new EditText(this);
            UiKit.styleInput(input);
            periodInputs[i] = input;
            row.addView(label);
            row.addView(input, weightedWrap());
            periodsCard.addView(row);
        }
        Button periodSave = new Button(this);
        periodSave.setText("저장");
        UiKit.stylePrimaryButton(periodSave);
        periodSave.setOnClickListener(v -> { saveNotifySettings(); Toast.makeText(this, "저장했어요.", Toast.LENGTH_SHORT).show(); });
        periodsCard.addView(periodSave, matchWrap());
        section.addView(periodsCard, cardLp());

        return section;
    }

    private LinearLayout buildMealSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setVisibility(View.GONE);
        section.setPadding(0, dp(4), 0, dp(16));

        LinearLayout keyCard = card();
        keyCard.addView(eyebrow("NEIS 공개 API 키"));
        TextView keyHint = new TextView(this);
        keyHint.setText("open.neis.go.kr 에서 무료로 발급받은 키를 입력하세요.");
        UiKit.styleCaption(keyHint);
        keyHint.setPadding(0, dp(2), 0, dp(8));
        keyCard.addView(keyHint);
        neisKeyInput = new EditText(this);
        neisKeyInput.setHint("API 키 붙여넣기");
        UiKit.styleInput(neisKeyInput);
        keyCard.addView(neisKeyInput);
        Button keySave = new Button(this);
        keySave.setText("저장");
        UiKit.stylePrimaryButton(keySave);
        keySave.setOnClickListener(v -> {
            prefs.setNeisApiKey(neisKeyInput.getText().toString().trim());
            Toast.makeText(this, "저장했어요.", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams ksLp = matchWrap();
        ksLp.topMargin = dp(8);
        keyCard.addView(keySave, ksLp);
        section.addView(keyCard, cardLp());

        return section;
    }

    private LinearLayout buildMappingSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        // No longer starts GONE: this used to be Settings accordion
        // content (hidden until its header was tapped), but it's now the
        // direct, only content of its own top-level tab (buildMapPage()),
        // so it needs to be visible by default like every other page.
        section.setPadding(0, dp(4), 0, dp(16));

        LinearLayout infoCard = card();
        infoCard.addView(eyebrow("실내 지도 데이터 수집 (실험 기능)"));
        TextView desc = new TextView(this);
        desc.setText("학교 실내 위치 지도를 만들기 위해 걸음 수와 방향, Wi-Fi 신호 세기를 기록해요. 기본적으로는 학교 근처(300m 이내)에 있을 때만 자동으로 기록하고, 그 밖에서는 기록하지 않아요. 서버로 보내지 않고, 특정 인물과 연결되지 않는 익명 데이터로 이 기기에만 저장해요. 동의하지 않으면 앱을 사용할 수 없어요.");
        UiKit.styleCaption(desc);
        desc.setPadding(0, dp(6), 0, 0);
        infoCard.addView(desc);

        alwaysRecordCheck = styledCheckbox("위치와 상관없이 항상 기록 (학교 위치 인식이 안 될 때 사용)");
        LinearLayout.LayoutParams alwaysRecordLp = matchWrap();
        alwaysRecordLp.topMargin = dp(10);
        alwaysRecordCheck.setChecked(prefs.testMode());
        alwaysRecordCheck.setOnCheckedChangeListener((b, checked) -> prefs.setTestMode(checked));
        infoCard.addView(alwaysRecordCheck, alwaysRecordLp);

        section.addView(infoCard, cardLp());

        LinearLayout statusCard = card();
        mappingStatusText = new TextView(this);
        UiKit.styleBody(mappingStatusText);
        mappingStatusText.setText("확인하는 중...");
        statusCard.addView(mappingStatusText);

        mappingGrantBtn = new Button(this);
        mappingGrantBtn.setText("권한 허용하고 시작");
        UiKit.stylePrimaryButton(mappingGrantBtn);
        LinearLayout.LayoutParams grantLp = matchWrap();
        grantLp.topMargin = dp(10);
        mappingGrantBtn.setOnClickListener(v -> requestAllPermissions(() -> {
            // Also (re-)asserts consent so BootReceiver/MappingWatchdogReceiver
            // (which gate on mappingConsentDone()) keep working even if this
            // button is how the user first actually got the service running.
            prefs.setMappingConsentDone(true);
            startMappingServiceIfPermitted();
            refreshMappingStatus();
        }));
        statusCard.addView(mappingGrantBtn, grantLp);

        mappingBatteryBtn = new Button(this);
        mappingBatteryBtn.setText("배터리 절전 최적화 예외로 설정");
        mappingBatteryBtn.setTextSize(12);
        UiKit.styleSecondaryButton(mappingBatteryBtn);
        LinearLayout.LayoutParams batteryLp = matchWrap();
        batteryLp.topMargin = dp(8);
        mappingBatteryBtn.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
        statusCard.addView(mappingBatteryBtn, batteryLp);
        section.addView(statusCard, cardLp());

        Button exportBtn = new Button(this);
        exportBtn.setText("움직임 기록 내보내기 (CSV)");
        UiKit.styleSecondaryButton(exportBtn);
        LinearLayout.LayoutParams exportLp = matchWrap();
        exportLp.bottomMargin = dp(16);
        exportBtn.setOnClickListener(v -> exportMappingCsv());
        section.addView(exportBtn, exportLp);

        LinearLayout sensorCard = card();
        sensorCard.addView(eyebrow("실시간 센서 값 (3D)"));
        mappingGizmoView = new OrientationGizmoView(this);
        LinearLayout.LayoutParams gizmoLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160));
        gizmoLp.topMargin = dp(8);
        sensorCard.addView(mappingGizmoView, gizmoLp);
        mappingSensorText = new TextView(this);
        UiKit.styleCaption(mappingSensorText);
        mappingSensorText.setTypeface(Typeface.MONOSPACE);
        mappingSensorText.setText("수집 중이 아니에요.");
        LinearLayout.LayoutParams sensorTextLp = matchWrap();
        sensorTextLp.topMargin = dp(8);
        sensorCard.addView(mappingSensorText, sensorTextLp);
        section.addView(sensorCard, cardLp());

        LinearLayout apRssiCard = card();
        apRssiCard.addView(eyebrow("실시간 Wi-Fi 신호 강도 (AP별)"));
        TextView apRssiHint = new TextView(this);
        apRssiHint.setText("연결된 AP(🔗)는 스캔이 아니라서 항상 실시간으로 갱신돼요. 나머지 목록은 Wi-Fi 스캔 결과라, 안드로이드의 스캔 제한이 켜져 있으면 30초에 한 번만 갱신돼요. 개발자 옵션 > 네트워크 > 'Wi-Fi 검색 제한'을 끄면 몇 초 간격으로 갱신되고 지도 정확도도 올라가요.");
        UiKit.styleCaption(apRssiHint);
        apRssiHint.setPadding(0, dp(2), 0, 0);
        apRssiCard.addView(apRssiHint);
        mappingApRssiText = new TextView(this);
        UiKit.styleCaption(mappingApRssiText);
        mappingApRssiText.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams apRssiTextLp = matchWrap();
        apRssiTextLp.topMargin = dp(8);
        apRssiCard.addView(mappingApRssiText, apRssiTextLp);
        section.addView(apRssiCard, cardLp());

        LinearLayout pathCard = card();
        pathCard.addView(eyebrow("이동 경로 (3D 평면도)"));
        mappingPathView = new MappingPathView(this);
        LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        pathLp.topMargin = dp(8);
        pathCard.addView(mappingPathView, pathLp);
        TextView pathLegend = new TextView(this);
        pathLegend.setText("● 원점 · ● 현재 위치 · ◆ 추정 Wi-Fi AP 위치\n손가락 1개로 이동 · 2개로 회전/기울기 · 오므리고 벌려서 확대축소 (구글 지도와 동일)");
        UiKit.styleCaption(pathLegend);
        pathLegend.setPadding(0, dp(4), 0, 0);
        pathCard.addView(pathLegend);

        Button resetViewBtn = new Button(this);
        resetViewBtn.setText("현위치 보기 (회전 초기화)");
        resetViewBtn.setTextSize(11);
        UiKit.styleSecondaryButton(resetViewBtn);
        LinearLayout.LayoutParams resetViewLp = matchWrap();
        resetViewLp.topMargin = dp(8);
        resetViewBtn.setOnClickListener(v -> {
            if (mappingPathView != null) mappingPathView.resetView();
        });
        pathCard.addView(resetViewBtn, resetViewLp);

        section.addView(pathCard, cardLp());

        LinearLayout rawCard = card();
        rawCard.addView(eyebrow("실제 원시 센서 데이터 (축별 변화 그래프)"));
        TextView rawLegend = new TextView(this);
        rawLegend.setText("X 빨강 · Y 초록 · Z 파랑");
        UiKit.styleCaption(rawLegend);
        rawCard.addView(rawLegend);
        accelGraph = new SparklineView(this, "🚶 가속도계", " m/s²");
        gyroGraph = new SparklineView(this, "🧭 자이로스코프", " rad/s");
        magGraph = new SparklineView(this, "🧭 자기장 센서", " μT");
        pressureGraph = new SparklineView(this, "📏 기압계", " hPa");
        rssiGraph = new SparklineView(this, "📶 Wi-Fi 최강 신호", " dBm");
        for (SparklineView gv : new SparklineView[]{accelGraph, gyroGraph, magGraph, pressureGraph, rssiGraph}) {
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
            glp.topMargin = dp(6);
            rawCard.addView(gv, glp);
        }
        Button snapshotBtn = new Button(this);
        snapshotBtn.setText("지금 센서값 기록 (디버그)");
        snapshotBtn.setTextSize(12);
        UiKit.styleSecondaryButton(snapshotBtn);
        LinearLayout.LayoutParams snapshotLp = matchWrap();
        snapshotLp.topMargin = dp(8);
        snapshotBtn.setOnClickListener(v -> {
            MappingCollector running = MappingService.getRunningCollector();
            if (running == null) {
                Toast.makeText(this, "아직 백그라운드 수집이 시작되지 않았어요.", Toast.LENGTH_SHORT).show();
                return;
            }
            running.snapshotSensors(new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new java.util.Date()));
            Toast.makeText(this, "센서값을 기록했어요.", Toast.LENGTH_SHORT).show();
            refreshMappingStatus();
        });
        rawCard.addView(snapshotBtn, snapshotLp);
        section.addView(rawCard, cardLp());

        LinearLayout gyroYawCard = card();
        gyroYawCard.addView(eyebrow("자이로 적분 방향 (보폭 계산과 별개)"));
        TextView gyroYawHint = new TextView(this);
        gyroYawHint.setText("나침반 보정 없이 자이로스코프만으로 적분한 방향이에요. 크게 튀거나 계속 한쪽으로 쏠리면 드리프트가 커지고 있다는 뜻이에요.");
        UiKit.styleCaption(gyroYawHint);
        gyroYawHint.setPadding(0, dp(2), 0, 0);
        gyroYawCard.addView(gyroYawHint);
        gyroYawGraph = new SparklineView(this, "🧭 자이로 전용 방향", "°");
        LinearLayout.LayoutParams gyroYawLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        gyroYawLp.topMargin = dp(8);
        gyroYawCard.addView(gyroYawGraph, gyroYawLp);
        section.addView(gyroYawCard, cardLp());

        LinearLayout strideCard = card();
        strideCard.addView(eyebrow("원점 기준 이동 거리 계산"));
        TextView strideHint = new TextView(this);
        strideHint.setText("걸음마다 가속도 변화폭으로 보폭을 자동 추정하고(고정값 입력 없음), 방향은 자기장 왜곡에 취약한 나침반 대신 자이로스코프를 적분해서 따라가요. Wi-Fi 스캔이 지문 지도와 맞을 때마다 위치를 살짝 보정해요.");
        UiKit.styleCaption(strideHint);
        strideHint.setPadding(0, dp(2), 0, 0);
        strideCard.addView(strideHint);
        mappingStrideText = new TextView(this);
        UiKit.styleBody(mappingStrideText);
        mappingStrideText.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams strideTextLp = matchWrap();
        strideTextLp.topMargin = dp(8);
        strideCard.addView(mappingStrideText, strideTextLp);
        Button resetOriginBtn = new Button(this);
        resetOriginBtn.setText("원점 재설정 (현재 위치를 0,0으로)");
        resetOriginBtn.setTextSize(12);
        UiKit.styleSecondaryButton(resetOriginBtn);
        LinearLayout.LayoutParams resetLp = matchWrap();
        resetLp.topMargin = dp(8);
        resetOriginBtn.setOnClickListener(v -> {
            MappingCollector running = MappingService.getRunningCollector();
            if (running == null) {
                Toast.makeText(this, "아직 백그라운드 수집이 시작되지 않았어요.", Toast.LENGTH_SHORT).show();
                return;
            }
            running.resetOrigin();
            Toast.makeText(this, "원점을 재설정했어요.", Toast.LENGTH_SHORT).show();
            refreshMappingStatus();
        });
        strideCard.addView(resetOriginBtn, resetLp);
        section.addView(strideCard, cardLp());

        LinearLayout waypointCard = card();
        waypointCard.addView(eyebrow("위치 이름표 · 위치 앵커 등록"));
        TextView waypointHint = new TextView(this);
        waypointHint.setText("이동 경로는 자동으로 기록돼요. 여기서 표시하면 이름표뿐 아니라 이 지점의 Wi-Fi 신호도 위치 기준점(앵커)으로 등록되어, 나중에 같은 곳으로 돌아오면 위치가 절대 위치에 가깝게 다시 보정돼요. 건물을 층별로 한 바퀴씩 돌면서 여러 곳에 표시해두면 정확도가 좋아져요.");
        UiKit.styleCaption(waypointHint);
        waypointHint.setPadding(0, dp(2), 0, 0);
        waypointCard.addView(waypointHint);
        LinearLayout wRow = new LinearLayout(this);
        wRow.setOrientation(LinearLayout.HORIZONTAL);
        wRow.setPadding(0, dp(6), 0, 0);
        mappingFloorInput = new EditText(this);
        mappingFloorInput.setHint("층 (예: 3층)");
        UiKit.styleInput(mappingFloorInput);
        LinearLayout.LayoutParams floorLp = weightedWrap();
        floorLp.rightMargin = dp(8);
        wRow.addView(mappingFloorInput, floorLp);
        mappingLabelInput = new EditText(this);
        mappingLabelInput.setHint("위치 (예: 3-1반 앞)");
        UiKit.styleInput(mappingLabelInput);
        wRow.addView(mappingLabelInput, weightedWrap());
        waypointCard.addView(wRow);

        Button waypointBtn = new Button(this);
        waypointBtn.setText("여기 표시");
        UiKit.styleSecondaryButton(waypointBtn);
        LinearLayout.LayoutParams waypointLp = matchWrap();
        waypointLp.topMargin = dp(8);
        waypointBtn.setOnClickListener(v -> {
            MappingCollector running = MappingService.getRunningCollector();
            if (running == null) {
                Toast.makeText(this, "아직 백그라운드 수집이 시작되지 않았어요.", Toast.LENGTH_SHORT).show();
                return;
            }
            String floor = mappingFloorInput.getText().toString().trim();
            String label = mappingLabelInput.getText().toString().trim();
            if (label.isEmpty()) {
                Toast.makeText(this, "위치를 입력해주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            running.addWaypoint(floor, label);
            running.addPlaceTag(floor, label);
            mappingLabelInput.setText("");
            Toast.makeText(this, "표시했어요: " + floor + " " + label, Toast.LENGTH_SHORT).show();
            refreshMappingStatus();
        });
        waypointCard.addView(waypointBtn, waypointLp);
        section.addView(waypointCard, cardLp());

        mappingCountsText = new TextView(this);
        UiKit.styleCaption(mappingCountsText);
        LinearLayout.LayoutParams countsLp = matchWrap();
        countsLp.topMargin = dp(4);
        section.addView(mappingCountsText, countsLp);

        Button apEstimatesBtn = new Button(this);
        apEstimatesBtn.setText("추정된 Wi-Fi 위치 보기");
        apEstimatesBtn.setTextSize(11);
        UiKit.styleSecondaryButton(apEstimatesBtn);
        LinearLayout.LayoutParams apBtnLp = matchWrap();
        apBtnLp.topMargin = dp(8);
        apEstimatesBtn.setOnClickListener(v -> showApEstimatesDialog());
        section.addView(apEstimatesBtn, apBtnLp);

        Button exportAllBtn = new Button(this);
        exportAllBtn.setText("전체 매핑 데이터 내보내기 (파일로 저장)");
        exportAllBtn.setTextSize(11);
        UiKit.styleSecondaryButton(exportAllBtn);
        LinearLayout.LayoutParams exportAllBtnLp = matchWrap();
        exportAllBtnLp.topMargin = dp(8);
        exportAllBtn.setOnClickListener(v -> exportMappingData());
        section.addView(exportAllBtn, exportAllBtnLp);

        return section;
    }

    // Dumps every mapping table (sessions/radio_scans/motion_samples/
    // waypoints/place_fingerprints) to one JSON file the user picks the
    // location and name for. This used to write to a fixed path under
    // app-specific external storage, which meant the file could only be
    // retrieved with a file manager that could reach Android/data (most
    // can't any more) or adb -- so the export was effectively
    // write-only. Now it goes through the same ACTION_CREATE_DOCUMENT
    // "save as" picker the CSV export uses: still no storage permission
    // on any supported API level, but the file lands wherever the user
    // wants it. The actual writing happens in onActivityResult().
    private void exportMappingData() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        String suggested = "mapping_export_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(new Date()) + ".json";
        intent.putExtra(Intent.EXTRA_TITLE, suggested);
        try {
            startActivityForResult(intent, REQ_EXPORT_ALL);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "파일 저장 앱을 찾을 수 없어요.", Toast.LENGTH_SHORT).show();
        }
    }

    // Adds a "live_sensor_data" section to the export with everything the
    // currently-running collector is tracking that ISN'T in MappingDb's
    // tables -- raw accelerometer/gyroscope/magnetometer/pressure/RSSI
    // rolling history (kept only in memory for the Settings debug graphs,
    // per MappingCollector's class doc) plus the latest single-sample
    // values and derived state (heading fusion reliability, gait/place
    // recognition). A no-op if collection isn't currently running -- the
    // DB dump above still has everything ever actually recorded to disk.
    private void addLiveSensorData(JSONObject data) throws org.json.JSONException {
        MappingCollector running = MappingService.getRunningCollector();
        if (running == null) return;

        JSONObject live = new JSONObject();
        live.put("history_count", running.getHistoryCount());
        live.put("accel_x_history", floatArrayToJson(running.getAccelXHistory()));
        live.put("accel_y_history", floatArrayToJson(running.getAccelYHistory()));
        live.put("accel_z_history", floatArrayToJson(running.getAccelZHistory()));
        live.put("gyro_x_history", floatArrayToJson(running.getGyroXHistory()));
        live.put("gyro_y_history", floatArrayToJson(running.getGyroYHistory()));
        live.put("gyro_z_history", floatArrayToJson(running.getGyroZHistory()));
        live.put("mag_x_history", floatArrayToJson(running.getMagXHistory()));
        live.put("mag_y_history", floatArrayToJson(running.getMagYHistory()));
        live.put("mag_z_history", floatArrayToJson(running.getMagZHistory()));
        live.put("gyro_yaw_history", floatArrayToJson(running.getGyroYawHistory()));
        live.put("pressure_history", floatArrayToJson(running.getPressureHistory()));
        live.put("rssi_history", floatArrayToJson(running.getRssiHistory()));

        live.put("heading_deg", running.getHeadingDeg());
        live.put("pitch_deg", running.getPitchDeg());
        live.put("roll_deg", running.getRollDeg());
        live.put("pressure_hpa", running.getPressureHpa());
        live.put("last_top_rssi", running.getLastTopRssi());
        live.put("screen_rotation_deg", running.getScreenRotationDeg());
        live.put("last_lat", Double.isNaN(running.getLastLat()) ? JSONObject.NULL : (Object) running.getLastLat());
        live.put("last_lon", Double.isNaN(running.getLastLon()) ? JSONObject.NULL : (Object) running.getLastLon());
        live.put("step_count", running.getStepCount());
        live.put("pos_x", running.getPosX());
        live.put("pos_y", running.getPosY());
        live.put("position_uncertainty_m", running.getPositionUncertaintyM());
        live.put("estimated_floor_delta", running.getEstimatedFloorDelta());
        live.put("last_step_length_m", running.getLastStepLengthM());
        live.put("is_stationary", running.isStationary());
        live.put("is_in_gait_streak", running.isInGaitStreak());
        live.put("is_magnetic_reliable", running.isMagneticReliable());

        MappingDb.PlaceMatch place = running.getLastPlaceMatch();
        if (place != null) {
            JSONObject placeJson = new JSONObject();
            placeJson.put("floor", place.floor);
            placeJson.put("label", place.label);
            placeJson.put("avg_match_distance", place.avgMatchDistance);
            live.put("last_place_match", placeJson);
        }

        JSONObject scanJson = new JSONObject();
        for (java.util.Map.Entry<String, Integer> e : running.getLastScanRssi().entrySet()) {
            scanJson.put(e.getKey(), e.getValue());
        }
        live.put("last_wifi_scan_rssi", scanJson);

        data.put("live_sensor_data", live);
    }

    private JSONArray floatArrayToJson(float[] values) throws org.json.JSONException {
        JSONArray arr = new JSONArray();
        for (float v : values) arr.put(v);
        return arr;
    }

    private void refreshMappingStatus() {
        if (mappingStatusText == null) return;
        boolean running = MappingService.getRunningCollector() != null;
        boolean permitted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && (Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED);
        if (running) {
            mappingStatusText.setText("🟢 백그라운드에서 항상 수집 중");
            mappingGrantBtn.setVisibility(View.GONE);
        } else if (permitted) {
            mappingStatusText.setText("권한은 있지만 아직 시작 전이에요.");
            mappingGrantBtn.setText("지금 시작");
            mappingGrantBtn.setVisibility(View.VISIBLE);
        } else {
            mappingStatusText.setText("권한이 필요해요.");
            mappingGrantBtn.setText("권한 허용하고 시작");
            mappingGrantBtn.setVisibility(View.VISIBLE);
        }
        if (mappingBatteryBtn != null) {
            mappingBatteryBtn.setVisibility(isIgnoringBatteryOptimizations() ? View.GONE : View.VISIBLE);
        }
        MappingDb mappingDb = new MappingDb(this);
        MappingDb.Counts c = mappingDb.counts();
        int fingerprints = mappingDb.fingerprintCount();
        int places = mappingDb.placeCount();
        mappingCountsText.setText("누적: 세션 " + c.sessions + "개 · 이동 기록 " + c.samples + "개 · Wi-Fi 스캔 " + c.scans
                + "개 · 지문(위치별 스캔) " + fingerprints + "개 · 이름표 " + c.waypoints + "개 · 장소 " + places
                + "개 · 센서값 기록 " + c.sensorSnapshots + "개");
        updateMappingLiveViews();
        refreshMappingPathView();
    }

    // DB-backed, so only called from the slow tick (see MAPPING_SLOW_TICKS)
    // instead of every fast tick like updateMappingLiveViews() below.
    private void refreshMappingPathView() {
        if (mappingPathView == null) return;
        MappingDb mappingDb = new MappingDb(this);
        mappingPathView.setApEstimates(mappingDb.estimateApPositions(3, 30));
        MappingCollector running = MappingService.getRunningCollector();
        if (running == null) {
            mappingPathView.setPath(new ArrayList<>(), 0, 0);
            mappingPathView.setFloorDelta(0);
            return;
        }
        List<double[]> path = mappingDb.pathForSession(running.getSessionId(), 20000);
        mappingPathView.setPath(path, running.getPosX(), running.getPosY());
        mappingPathView.setFloorDelta(running.getEstimatedFloorDelta());
    }

    // Pulls the current heading/pitch/roll/step/position readout, 3D
    // gizmo, and raw-data graphs straight from the live collector's
    // in-memory fields -- no DB hit, so this is cheap enough to call at
    // MAPPING_TICK_MS. The path drawing needs actual history from the DB;
    // see refreshMappingPathView() above for that slower-cadence piece.
    private void updateMappingLiveViews() {
        if (mappingSensorText == null) return;
        MappingCollector running = MappingService.getRunningCollector();
        if (running == null) {
            mappingSensorText.setText("수집 중이 아니에요.");
            if (mappingStrideText != null) mappingStrideText.setText("");
            if (mappingApRssiText != null) mappingApRssiText.setText("");
            if (mappingGizmoView != null) mappingGizmoView.setOrientation(0, 0, 0);
            for (SparklineView gv : new SparklineView[]{accelGraph, gyroGraph, magGraph, pressureGraph, rssiGraph, gyroYawGraph}) {
                if (gv != null) gv.setSeries(new ArrayList<>(), 0);
            }
            return;
        }
        float heading = running.getHeadingDeg();
        float pitch = running.getPitchDeg();
        float roll = running.getRollDeg();
        int steps = running.getStepCount();
        double x = running.getPosX();
        double y = running.getPosY();
        double dist = Math.sqrt(x * x + y * y);
        String gps = Double.isNaN(running.getLastLat()) ? "미확보"
                : String.format(Locale.KOREA, "%.5f, %.5f", running.getLastLat(), running.getLastLon());
        MappingDb.PlaceMatch placeMatch = running.getLastPlaceMatch();
        String placeStr = placeMatch == null ? "알 수 없음"
                : String.format(Locale.KOREA, "%s %s (거리 %.1f)", placeMatch.floor, placeMatch.label, placeMatch.avgMatchDistance);
        mappingSensorText.setText(String.format(Locale.KOREA,
                "방위(heading) %.0f°  기울기(pitch) %.0f°  좌우기울기(roll) %.0f°\n" +
                        "걸음 수 %d  위치 (%.1f, %.1f) m ±%.1fm  원점에서 %.1fm\n" +
                        "추정 층 변화 %+d층 (기준 대비 %+.2f층, 수직가속 %+.2f m/s²)\n" +
                        "화면 방향 %d°  GPS %s\n" +
                        "정지 상태 %s  보행 확정 %s  나침반 신뢰 %s\n" +
                        "Wi-Fi 추정 장소 %s",
                heading, pitch, roll, steps, x, y, running.getPositionUncertaintyM(), dist,
                running.getEstimatedFloorDelta(), running.getFloorOffsetRaw(), running.getVertAccel(),
                running.getScreenRotationDeg(), gps,
                running.isStationary() ? "예" : "아니오", running.isInGaitStreak() ? "예" : "아니오",
                running.isMagneticReliable() ? "예" : "아니오",
                placeStr));
        if (mappingGizmoView != null) mappingGizmoView.setOrientation(heading, pitch, roll);
        if (mappingStrideText != null) {
            // Was a hardcoded "자이로 적분" label regardless of which
            // source was actually steering direction -- now reflects the
            // real live choice (see MappingCollector.applyStep()'s dirDeg
            // selection), the same condition that decides it there.
            String dirSource = running.isMagneticReliable() ? "나침반" : "자이로 적분 (나침반 불안정)";
            mappingStrideText.setText(String.format(Locale.KOREA,
                    "최근 걸음 보폭(자동 추정) %.2fm  ·  방향 소스: %s\n" +
                            "Wi-Fi로 학습한 방향 보정: %+.1f°",
                    running.getLastStepLengthM(), dirSource, running.getHeadingBiasDeg()));
        }
        if (mappingApRssiText != null) {
            mappingApRssiText.setText(formatApRssiList(running));
        }

        running.pushRawHistorySample();
        int histCount = running.getHistoryCount();
        final int RED = 0xFFFF7A7A, GREEN = 0xFF57C785, BLUE = 0xFF5B8CFF;
        if (accelGraph != null) accelGraph.setSeries(java.util.Arrays.asList(
                new SparklineView.Series(running.getAccelXHistory(), RED, "X"),
                new SparklineView.Series(running.getAccelYHistory(), GREEN, "Y"),
                new SparklineView.Series(running.getAccelZHistory(), BLUE, "Z")), histCount);
        if (gyroGraph != null) gyroGraph.setSeries(java.util.Arrays.asList(
                new SparklineView.Series(running.getGyroXHistory(), RED, "X"),
                new SparklineView.Series(running.getGyroYHistory(), GREEN, "Y"),
                new SparklineView.Series(running.getGyroZHistory(), BLUE, "Z")), histCount);
        if (magGraph != null) magGraph.setSeries(java.util.Arrays.asList(
                new SparklineView.Series(running.getMagXHistory(), RED, "X"),
                new SparklineView.Series(running.getMagYHistory(), GREEN, "Y"),
                new SparklineView.Series(running.getMagZHistory(), BLUE, "Z")), histCount);
        if (pressureGraph != null) pressureGraph.setSeries(java.util.Collections.singletonList(
                new SparklineView.Series(running.getPressureHistory(), 0xFFF2B94C, null)), histCount);
        if (rssiGraph != null) rssiGraph.setSeries(java.util.Collections.singletonList(
                new SparklineView.Series(running.getRssiHistory(), 0xFFB57BFF, null)), histCount);
        if (gyroYawGraph != null) gyroYawGraph.setSeries(java.util.Collections.singletonList(
                new SparklineView.Series(running.getGyroYawHistory(), 0xFF4CD3C2, null)), histCount);
    }

    // Individual per-AP signal strength for mappingApRssiText -- the
    // sensor readout above only ever showed lastTopRssi (the single
    // strongest signal), collapsing away every other AP actually seen.
    // Sorted strongest-first and capped so a busy Wi-Fi environment
    // doesn't overflow the card; SSID falls back to the BSSID's last 5
    // characters for a hidden/blank network, which is still enough to
    // tell two same-named APs apart at a glance.
    private static final int AP_RSSI_LIST_MAX = 8;

    private String formatApRssiList(MappingCollector running) {
        StringBuilder sb = new StringBuilder();
        // The connected AP first and separately: it's the one value here
        // that isn't throttled to the 30s scan cadence, so it actually
        // changes every tick (see MappingCollector.pollConnectedRssi()).
        int connRssi = running.getConnectedRssi();
        if (connRssi != 0) {
            String connSsid = running.getConnectedSsid();
            if (connSsid == null || connSsid.isEmpty()) connSsid = "(연결됨)";
            sb.append(String.format(Locale.KOREA, "🔗 %-18s %4d dBm  ← 실시간\n\n", connSsid, connRssi));
        }
        // Measured, not assumed: a throttled startScan() returns stale
        // results rather than an error, so counting scans that actually
        // brought new data is the only way to tell from inside the app
        // whether this device enforces the 4-per-2-minutes quota.
        int fresh = running.getFreshScansLast2Min();
        sb.append(String.format(Locale.KOREA,
                "실제 새 스캔: 최근 2분간 %d회 %s\n\n",
                fresh, fresh <= 5 ? "(스캔 제한 켜짐 — 4회가 한도)" : "(스캔 제한 꺼짐 — 최대 속도로 수집 중)"));
        sb.append(formatScannedApList(running));
        return sb.toString();
    }

    private String formatScannedApList(MappingCollector running) {
        java.util.Map<String, Integer> rssiMap = running.getLastScanRssi();
        if (rssiMap.isEmpty()) return "아직 스캔 결과가 없어요.";
        java.util.Map<String, String> ssidMap = running.getLastScanSsidByBssid();
        List<java.util.Map.Entry<String, Integer>> sorted = new ArrayList<>(rssiMap.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (java.util.Map.Entry<String, Integer> e : sorted) {
            if (shown >= AP_RSSI_LIST_MAX) break;
            String bssid = e.getKey();
            String ssid = ssidMap.get(bssid);
            if (ssid == null || ssid.isEmpty() || ssid.equals("<unknown ssid>")) {
                ssid = bssid.length() >= 5 ? "(" + bssid.substring(bssid.length() - 5) + ")" : "(숨김)";
            }
            if (shown > 0) sb.append('\n');
            sb.append(String.format(Locale.KOREA, "%-20s %4d dBm", ssid, e.getValue()));
            shown++;
        }
        if (rssiMap.size() > AP_RSSI_LIST_MAX) {
            sb.append(String.format(Locale.KOREA, "\n… 외 %d개", rssiMap.size() - AP_RSSI_LIST_MAX));
        }
        return sb.toString();
    }

    // Shows each Wi-Fi access point's estimated position (RSSI-weighted
    // centroid of every spot it was observed from -- see
    // MappingDb.estimateApPositions) as a quick sanity check on the
    // collected data, ranked by how many times it's been seen.
    private void showApEstimatesDialog() {
        List<MappingDb.ApEstimate> estimates = new MappingDb(this).estimateApPositions(3, 30);
        Dialog dialog = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiKit.card());
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("추정된 Wi-Fi 위치");
        title.setTextColor(UiKit.TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(16);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("세션 시작 지점 기준 상대 좌표(m). 최소 3번 이상 관측된 AP만 표시해요.");
        UiKit.styleCaption(hint);
        hint.setPadding(0, 0, 0, dp(10));
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        if (estimates.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("아직 추정할 만큼 데이터가 모이지 않았어요.");
            UiKit.styleCaption(empty);
            list.addView(empty);
        } else {
            for (MappingDb.ApEstimate est : estimates) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, dp(6), 0, dp(6));
                TextView bssidText = new TextView(this);
                bssidText.setText(est.bssid);
                bssidText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                bssidText.setTextColor(UiKit.TEXT_PRIMARY);
                bssidText.setTextSize(13);
                row.addView(bssidText);
                TextView detail = new TextView(this);
                detail.setText(String.format(Locale.KOREA, "(%.1f, %.1f) · 관측 %d회 · 평균 %.0fdBm",
                        est.x, est.y, est.observations, est.avgRssi));
                UiKit.styleCaption(detail);
                row.addView(detail);
                list.addView(row);
            }
        }
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(400)));

        dialog.setContentView(root);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        dialog.show();
    }

    private AutoCompleteTextView makePickerInput(String[] options) {
        AutoCompleteTextView v = new AutoCompleteTextView(this);
        v.setInputType(InputType.TYPE_CLASS_NUMBER);
        UiKit.styleInput(v);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, options);
        v.setAdapter(adapter);
        v.setThreshold(0);
        v.setOnClickListener(view -> v.showDropDown());
        v.setOnFocusChangeListener((view, hasFocus) -> { if (hasFocus) v.showDropDown(); });
        return v;
    }

    private void doSearch() {
        String q = searchInput.getText().toString().trim();
        if (q.isEmpty()) return;
        searchResults.removeAllViews();
        searchResults.addView(loadingRow("검색하는 중..."));
        ComciganApi.searchSchools(q, (schools, err) -> {
            searchResults.removeAllViews();
            if (err != null) { searchResults.addView(errorRow("검색하지 못했어요: " + err.getMessage())); return; }
            if (schools.isEmpty()) { searchResults.addView(errorRow("찾은 학교가 없어요.")); return; }
            for (ComciganApi.School s : schools) searchResults.addView(schoolResultRow(s));
        });
    }

    private View schoolResultRow(ComciganApi.School s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiKit.secondaryButtonBg());
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        UiKit.attachBouncyPress(row);

        TextView region = new TextView(this);
        region.setText(s.region);
        UiKit.styleCaption(region);
        region.setWidth(dp(56));
        row.addView(region);

        TextView name = new TextView(this);
        name.setText(s.name);
        UiKit.styleBody(name);
        row.addView(name, weightedWrap());

        row.setOnClickListener(v -> {
            pendingSchoolCode = s.code;
            pendingSchoolName = s.name;
            selectedSchoolLabel.setText(s.name);
            selectedSchoolLabel.setTextColor(UiKit.TEXT_PRIMARY);
            Toast.makeText(this, s.name + " 선택됨", Toast.LENGTH_SHORT).show();
        });
        return row;
    }

    private void saveSetup() {
        if (pendingSchoolCode.isEmpty()) {
            Toast.makeText(this, "학교를 먼저 검색해서 선택해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.setSchool(pendingSchoolCode, pendingSchoolName);
        // Also fixes indoor mapping never collecting when a school is set
        // from here instead of first-run onboarding (the only other place
        // this was called from): with no lat/lon ever stored, the
        // geofence in MappingService.shouldCollect() always failed.
        updateSchoolLocation(pendingSchoolName);
        try {
            int g = Integer.parseInt(gradeAuto.getText().toString().trim());
            int c = Integer.parseInt(classAuto.getText().toString().trim());
            prefs.setClass(g, c);
        } catch (Exception ignored) {}
        prefs.setTeacherMode(teacherModeCheck.isChecked());
        prefs.setTeacherName(teacherNameInput.getText().toString().trim());
        NotificationScheduler.rescheduleAll(this);
        if (prefs.liveNotify()) startForegroundService(new Intent(this, LiveNotifyService.class));
        Toast.makeText(this, "적용했어요.", Toast.LENGTH_SHORT).show();
        showPage(TAB_TIMETABLE);
    }

    private void saveCurrentAsNamedClass() {
        if (pendingSchoolCode.isEmpty()) {
            Toast.makeText(this, "학교를 먼저 검색해서 선택해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        int g, c;
        try {
            g = Integer.parseInt(gradeAuto.getText().toString().trim());
            c = Integer.parseInt(classAuto.getText().toString().trim());
        } catch (Exception e) {
            Toast.makeText(this, "학년/반을 확인해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        String label = pendingSchoolName + " " + g + "학년 " + c + "반";
        List<Prefs.SavedClass> list = prefs.savedClasses();
        list.add(new Prefs.SavedClass(label, pendingSchoolCode, pendingSchoolName, g, c));
        prefs.saveSavedClasses(list);
        refreshSavedClassesList();
        Toast.makeText(this, "저장했어요: " + label, Toast.LENGTH_SHORT).show();
    }

    private void refreshSavedClassesList() {
        savedClassesList.removeAllViews();
        List<Prefs.SavedClass> list = prefs.savedClasses();
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("저장된 학급이 없어요.");
            UiKit.styleCaption(empty);
            savedClassesList.addView(empty);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            Prefs.SavedClass sc = list.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(UiKit.secondaryButtonBg());
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams lp = matchWrap();
            lp.topMargin = dp(6);
            row.setLayoutParams(lp);

            TextView label = new TextView(this);
            label.setText(sc.label);
            UiKit.styleBody(label);
            row.addView(label, weightedWrap());

            Button applyBtn = new Button(this);
            applyBtn.setText("적용");
            applyBtn.setTextSize(11);
            UiKit.styleSecondaryButton(applyBtn);
            applyBtn.setOnClickListener(v -> {
                pendingSchoolCode = sc.schoolCode;
                pendingSchoolName = sc.schoolName;
                selectedSchoolLabel.setText(sc.schoolName);
                selectedSchoolLabel.setTextColor(UiKit.TEXT_PRIMARY);
                gradeAuto.setText(String.valueOf(sc.grade));
                classAuto.setText(String.valueOf(sc.classNum));
                saveSetup();
            });
            row.addView(applyBtn);

            Button delBtn = new Button(this);
            delBtn.setText("삭제");
            delBtn.setTextSize(11);
            UiKit.styleSecondaryButton(delBtn);
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            delLp.leftMargin = dp(6);
            delBtn.setOnClickListener(v -> {
                List<Prefs.SavedClass> cur = prefs.savedClasses();
                cur.remove(idx);
                prefs.saveSavedClasses(cur);
                refreshSavedClassesList();
            });
            row.addView(delBtn, delLp);

            savedClassesList.addView(row);
        }
    }

    private void refreshColorsList() {
        if (colorsList == null) return;
        colorsList.removeAllViews();
        List<String> subjects = new ArrayList<>(prefs.knownSubjects());
        java.util.Collections.sort(subjects);
        if (subjects.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("시간표를 한 번 불러오면 여기 나타나요.");
            UiKit.styleCaption(empty);
            colorsList.addView(empty);
            return;
        }
        for (String subject : subjects) colorsList.addView(subjectColorRow(subject));
    }

    private View subjectColorRow(String subject) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView name = new TextView(this);
        name.setText(subject);
        UiKit.styleBody(name);
        row.addView(name, weightedWrap());

        View swatch = new View(this);
        GradientDrawable swatchBg = new GradientDrawable();
        swatchBg.setColor(prefs.subjectColor(subject));
        swatchBg.setCornerRadius(dp(8));
        swatch.setBackground(swatchBg);
        LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        swatch.setLayoutParams(swatchLp);
        swatch.setClickable(true);
        swatch.setFocusable(true);
        UiKit.attachBouncyPress(swatch);
        swatch.setOnClickListener(v -> UiKit.showColorPicker(this, color -> {
            prefs.setSubjectColor(subject, color);
            refreshColorsList();
        }));
        row.addView(swatch);
        return row;
    }

    private CheckBox styledCheckbox(String text) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setTextColor(UiKit.TEXT_PRIMARY);
        cb.setPadding(dp(4), dp(6), 0, dp(6));
        return cb;
    }

    private void saveNotifySettings() {
        prefs.setNotifyChange(notifyChangeCheck.isChecked());
        prefs.setNotifyPeriod(notifyPeriodCheck.isChecked());
        prefs.setNotifyMorning(notifyMorningCheck.isChecked());
        prefs.setLiveNotify(liveNotifyCheck.isChecked());
        String morning = morningTimeInput.getText().toString().trim();
        if (morning.matches("\\d{1,2}:\\d{2}")) prefs.setMorningTime(morning);
        for (int i = 0; i < 8; i++) {
            String v = periodInputs[i].getText().toString().trim();
            if (v.matches("\\d{1,2}:\\d{2}-\\d{1,2}:\\d{2}")) prefs.setPeriodTime(i + 1, v);
        }
        NotificationScheduler.rescheduleAll(this);
        if (prefs.liveNotify()) {
            startForegroundService(new Intent(this, LiveNotifyService.class));
        } else {
            stopService(new Intent(this, LiveNotifyService.class));
        }
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(UiKit.card());
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        // Real shader refraction is deliberately NOT applied here -- with
        // dozens of cards on screen at once (settings sections, weekly
        // schedule cards) each would need its own RenderEffect compositing
        // layer, which is a real GPU cost for a barely-visible effect on a
        // large, mostly-flat surface. It's reserved for small, individually
        // meaningful controls (buttons/toggles -- see UiKit.stylePrimaryButton/
        // styleSecondaryButton -- and the bottom nav bar) where it actually reads.
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(16);
        return lp;
    }

    private TextView eyebrow(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        UiKit.styleEyebrow(t);
        return t;
    }

    private TextView fieldLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(UiKit.TEXT_SECONDARY);
        t.setTextSize(12);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    private View loadingRow(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        UiKit.styleCaption(t);
        t.setPadding(dp(4), dp(8), dp(4), dp(8));
        return t;
    }

    private View errorRow(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setBackground(UiKit.card());
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(UiKit.TEXT_SECONDARY);
        t.setTextSize(13);
        row.addView(t);
        return row;
    }

    private void loadPrefsIntoUi() {
        if (!prefs.schoolCode().isEmpty()) {
            pendingSchoolCode = prefs.schoolCode();
            pendingSchoolName = prefs.schoolName();
            selectedSchoolLabel.setText(prefs.schoolName());
            selectedSchoolLabel.setTextColor(UiKit.TEXT_PRIMARY);
        }
        gradeAuto.setText(String.valueOf(prefs.grade()) + "학년");
        classAuto.setText(String.valueOf(prefs.classNum()) + "반");
        refreshSavedClassesList();

        notifyChangeCheck.setChecked(prefs.notifyChange());
        notifyPeriodCheck.setChecked(prefs.notifyPeriod());
        notifyMorningCheck.setChecked(prefs.notifyMorning());
        liveNotifyCheck.setChecked(prefs.liveNotify());
        solidColorCheck.setChecked(prefs.solidTimetableColor());
        morningTimeInput.setText(prefs.morningTime());
        for (int i = 0; i < 8; i++) periodInputs[i].setText(prefs.periodTime(i + 1));
        neisKeyInput.setText(prefs.neisApiKey());

        if (teacherModeCheck != null) {
            teacherModeCheck.setChecked(prefs.isTeacherMode());
            teacherNameInput.setText(prefs.teacherName());
            studentSettingsBox.setVisibility(prefs.isTeacherMode() ? View.GONE : View.VISIBLE);
            teacherSettingsBox.setVisibility(prefs.isTeacherMode() ? View.VISIBLE : View.GONE);
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightedWrap() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void updateSchoolLocation(String schoolName) {
        if (schoolName == null || schoolName.isEmpty()) return;
        new Thread(() -> {
            try {
                Geocoder geocoder = new Geocoder(this, Locale.KOREA);
                List<Address> addresses = geocoder.getFromLocationName(schoolName, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address addr = addresses.get(0);
                    prefs.setSchoolLocation((float) addr.getLatitude(), (float) addr.getLongitude());
                    runOnUiThread(() -> Toast.makeText(this, "학교 위치가 자동으로 설정되었습니다.", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void requestAllPermissions(Runnable onDone) {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }

        if (needed.isEmpty()) {
            onDone.run();
            return;
        }
        pendingMappingStart = onDone;
        requestPermissions(needed.toArray(new String[0]), REQ_MAPPING_PERMS);
    }

    private int dp(int v) { return UiKit.dp(v); }
}
