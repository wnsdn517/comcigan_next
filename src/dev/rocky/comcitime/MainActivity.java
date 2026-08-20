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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private Prefs prefs;

    private LinearLayout[] pages;
    private Button[] tabButtons;
    private static final String[] TAB_NAMES = {"시간표", "급식", "설정"};

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
    private EditText morningTimeInput;
    private EditText[] periodInputs = new EditText[8];
    private EditText neisKeyInput;

    private TextView mealStatusText;
    private LinearLayout mealContent;

    private FrameLayout onboardingOverlay;
    private FrameLayout teacherPreviewOverlay;
    private LinearLayout teacherPreviewContent;
    private TextView teacherPreviewTitle;

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

        pages = new LinearLayout[]{buildTimetablePage(), buildMealPage(), buildSettingsPage()};
        FrameLayout container = new FrameLayout(this);
        for (LinearLayout p : pages) {
            container.addView(p, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        outerCol.addView(container, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        outerCol.addView(buildBottomNav());

        rootFrame.addView(outerCol, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootFrame.addView(buildTeacherPreviewOverlay(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootFrame.addView(buildOnboardingOverlay(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootFrame);
        loadPrefsIntoUi();
        showPage(prefs.schoolCode().isEmpty() ? 2 : 0);

        if (prefs.onboardingDone()) {
            onboardingOverlay.setVisibility(View.GONE);
            requestNotifPermissionIfNeeded();
        }
    }

    private void requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }

    private LinearLayout buildBottomNav() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setBackgroundColor(UiKit.SURFACE);
        wrap.setPadding(dp(8), dp(10), dp(8), dp(10));

        tabButtons = new Button[TAB_NAMES.length];
        for (int i = 0; i < TAB_NAMES.length; i++) {
            Button b = new Button(this);
            b.setText(TAB_NAMES[i]);
            b.setTextSize(13);
            b.setAllCaps(false);
            b.setElevation(0);
            b.setStateListAnimator(null);
            b.setBackground(null);
            final int idx = i;
            b.setOnClickListener(v -> { UiKit.popIn(v); showPage(idx); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            wrap.addView(b, lp);
            tabButtons[i] = b;
        }
        return wrap;
    }

    private void showPage(int index) {
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            tabButtons[i].setTextColor(i == index ? UiKit.ACCENT : UiKit.TEXT_SECONDARY);
            tabButtons[i].setTypeface(i == index ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        nowPanelHandler.removeCallbacksAndMessages(null);
        if (index == 0) { loadAllWeeks(); tickNowPanel(); }
        if (index == 1) refreshMeal();
        if (index == 2) refreshColorsList();
    }

    private FrameLayout buildOnboardingOverlay() {
        onboardingOverlay = new FrameLayout(this);
        onboardingOverlay.setBackgroundColor(UiKit.BG);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(40), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("시작하기 전에");
        title.setTextColor(UiKit.TEXT_PRIMARY);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title);

        LinearLayout infoCard = card();
        infoCard.addView(onboardingLine("무엇을 하나요", "컴시간(comci.kr) 공개 서비스에서 학교 시간표를 가져와 보여주고, 정해두신 시각에 알림을 보내드려요."));
        infoCard.addView(onboardingLine("어떤 정보를 쓰나요", "선택하신 학교 코드, 학년, 반, 직접 추가한 개인 일정을 이 기기 안에만 저장합니다."));
        infoCard.addView(onboardingLine("알림은 어떻게 오나요", "정해진 시각에 앱이 자동으로 시간표를 확인해 알림으로 알려드려요."));
        infoCard.addView(onboardingLine("급식 정보(선택)", "설정 탭에서 NEIS 공개 API 키를 직접 입력하시면 급식 정보도 볼 수 있어요."));
        root.addView(infoCard, cardLp());

        CheckBox agree = new CheckBox(this);
        agree.setText("위 내용에 동의하고 시작할게요");
        agree.setTextColor(UiKit.TEXT_PRIMARY);
        root.addView(agree);

        Button startBtn = new Button(this);
        startBtn.setText("시작하기");
        UiKit.stylePrimaryButton(startBtn);
        startBtn.setEnabled(false);
        startBtn.setAlpha(0.5f);
        LinearLayout.LayoutParams startLp = matchWrap();
        startLp.topMargin = dp(16);
        root.addView(startBtn, startLp);

        agree.setOnCheckedChangeListener((b, checked) -> {
            startBtn.setEnabled(checked);
            startBtn.setAlpha(checked ? 1f : 0.5f);
        });
        startBtn.setOnClickListener(v -> {
            prefs.setOnboardingDone(true);
            onboardingOverlay.setVisibility(View.GONE);
            requestNotifPermissionIfNeeded();
        });

        scroll.addView(root);
        onboardingOverlay.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return onboardingOverlay;
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
        headerClickable.setOnClickListener(v -> showPage(2));
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
        hint.setText("길게 눌러 위로 밀면 선생님 시간표 고정 · 탭 한 번은 일정 추가 · 좌우로 밀면 옆 반");
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
        if (prefs.schoolCode().isEmpty() || weekSections.isEmpty() || viewingTeacherName != null) {
            nowPanel.setVisibility(View.GONE);
            return;
        }
        int dow = mondayBasedDow();
        if (dow == 0) { nowPanel.setVisibility(View.GONE); return; }

        Timetable.PeriodEntry current = null;
        Timetable tt = weekSections.get(0).tt;
        List<Timetable.PeriodEntry> today = tt.getDaySchedule(prefs.grade(), prefs.classNum(), dow);
        Calendar now = Calendar.getInstance();
        int nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        for (Timetable.PeriodEntry e : today) {
            Integer[] range = parsePeriodRange(prefs.periodTime(e.period));
            if (range == null) continue;
            if (nowMinutes >= range[0] && nowMinutes < range[1]) { current = e; break; }
        }
        if (current == null) { nowPanel.setVisibility(View.GONE); return; }

        nowPanel.removeAllViews();
        nowPanel.setVisibility(View.VISIBLE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(blend(prefs.subjectColor(current.subject), UiKit.SURFACE, 0.35f));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(2), UiKit.ACCENT);
        nowPanel.setBackground(bg);
        nowPanel.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView live = new TextView(this);
        live.setText("\ud83d\udd34 지금 " + current.period + "교시");
        live.setTextColor(UiKit.ACCENT);
        live.setTypeface(Typeface.DEFAULT_BOLD);
        live.setTextSize(12);
        nowPanel.addView(live);

        TextView subj = new TextView(this);
        subj.setText(current.subject + (current.teacher.isEmpty() ? "" : "  \u00b7  " + current.teacher));
        subj.setTextColor(Color.WHITE);
        subj.setTextSize(18);
        subj.setTypeface(Typeface.DEFAULT_BOLD);
        subj.setPadding(0, dp(4), 0, 0);
        nowPanel.addView(subj);

        String today2 = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new java.util.Date());
        Prefs.PersonalEvent ev = prefs.findPersonalEvent(today2, current.period);
        if (ev != null) {
            TextView evText = new TextView(this);
            evText.setText("\ud83d\udcdd " + ev.text);
            evText.setTextColor(0xDDFFFFFF);
            evText.setTextSize(12);
            evText.setPadding(0, dp(4), 0, 0);
            nowPanel.addView(evText);
        }
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

    private void loadAllWeeks() {
        if (prefs.schoolCode().isEmpty()) {
            classHeaderLabel.setText("학급을 설정해주세요 \u203a");
            weekSectionsContainer.removeAllViews();
            weekSectionsContainer.addView(errorRow("설정 탭에서 학교와 반을 먼저 설정해주세요."));
            prevWeekBtn.setVisibility(View.GONE);
            return;
        }
        browseClassNum = prefs.classNum();
        viewingTeacherName = null;
        teacherViewLocked = false;
        classHeaderLabel.setText(prefs.grade() + "학년 " + browseClassNum + "반 \u203a");
        teacherModeIndicator.setVisibility(View.GONE);
        weekSectionsContainer.removeAllViews();
        weekSections.clear();
        prevWeekBtn.setVisibility(prefs.archivedWeekDates().isEmpty() ? View.GONE : View.VISIBLE);
        weekSectionsContainer.addView(loadingRow("시간표를 불러오는 중..."));

        TimetableRepository.fetch(this, "1", (tt, offline, err) -> {
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
            loadWeeksSequentially(weeks, 0, offline);
        });
    }

    private void loadWeeksSequentially(List<Timetable.WeekOption> weeks, int index, boolean firstOffline) {
        if (index >= weeks.size()) { renderNowPanel(); return; }
        Timetable.WeekOption w = weeks.get(index);
        TimetableRepository.fetch(this, w.code, (tt, offline, err) -> {
            if (tt != null) addWeekSection(tt, index, offline);
            loadWeeksSequentially(weeks, index + 1, firstOffline);
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
        attachCellTouch(cell, e, date, period);
        return cell;
    }

    private void attachCellTouch(View cell, Timetable.PeriodEntry e, String date, int period) {
        final long[] startTime = {0};
        final float[] startY = {0};
        final boolean[] longPressFired = {false};
        final boolean[] moved = {false};
        Handler handler = new Handler();
        final Runnable[] pending = new Runnable[1];

        cell.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startTime[0] = System.currentTimeMillis();
                    startY[0] = event.getRawY();
                    longPressFired[0] = false;
                    moved[0] = false;
                    v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start();
                    if (e != null && !e.teacher.isEmpty()) {
                        String teacherName = e.teacher;
                        pending[0] = () -> {
                            longPressFired[0] = true;
                            enterTeacherViewTemp(teacherName);
                        };
                        handler.postDelayed(pending[0], 420);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dy = startY[0] - event.getRawY();
                    if (longPressFired[0] && !teacherViewLocked && dy > dp(70)) {
                        lockTeacherView();
                    } else if (!longPressFired[0] && Math.abs(dy) > dp(18)) {
                        moved[0] = true;
                        if (pending[0] != null) handler.removeCallbacks(pending[0]);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                            .setInterpolator(new OvershootInterpolator(4f)).start();
                    if (pending[0] != null) handler.removeCallbacks(pending[0]);
                    if (longPressFired[0]) {
                        if (!teacherViewLocked) exitTeacherView();
                    } else if (!moved[0]) {
                        if (viewingTeacherName != null) {
                            exitTeacherView();
                        } else if (!date.isEmpty()) {
                            openEventDialog(date, period, e);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    if (pending[0] != null) handler.removeCallbacks(pending[0]);
                    if (longPressFired[0] && !teacherViewLocked) exitTeacherView();
                    return true;
            }
            return false;
        });
    }

    private View gridCellForTeacher(Timetable.TeacherPeriodEntry e) {
        LinearLayout cell = emptyCell();
        if (e != null) fillCell(cell, e.subject, e.grade + "-" + e.classNum, e.changed);
        cell.setOnClickListener(v -> exitTeacherView());
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
        bg.setCornerRadius(dp(6));
        cell.setBackground(bg);
        return cell;
    }

    private void fillCell(LinearLayout cell, String subject, String subLabel, boolean changed) {
        int color = prefs.subjectColor(subject);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(blend(color, UiKit.SURFACE, 0.72f));
        bg.setCornerRadius(dp(6));
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

    private FrameLayout buildTeacherPreviewOverlay() {
        teacherPreviewOverlay = new FrameLayout(this);
        teacherPreviewOverlay.setBackgroundColor(0xCC000000);
        teacherPreviewOverlay.setVisibility(View.GONE);
        teacherPreviewOverlay.setClickable(true);
        teacherPreviewOverlay.setOnClickListener(v -> exitTeacherView());

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(UiKit.card());
        sheet.setPadding(dp(16), dp(16), dp(16), dp(16));
        sheet.setClickable(true);
        sheet.setOnClickListener(v -> {}); // swallow taps inside the sheet itself

        teacherPreviewTitle = new TextView(this);
        teacherPreviewTitle.setTextColor(UiKit.TEXT_PRIMARY);
        teacherPreviewTitle.setTypeface(Typeface.DEFAULT_BOLD);
        teacherPreviewTitle.setTextSize(16);
        sheet.addView(teacherPreviewTitle);

        TextView tapHint = new TextView(this);
        tapHint.setText("바깥을 탭하면 닫혀요");
        tapHint.setTextColor(UiKit.TEXT_SECONDARY);
        tapHint.setTextSize(11);
        tapHint.setPadding(0, dp(2), 0, dp(10));
        sheet.addView(tapHint);

        ScrollView scroll = new ScrollView(this);
        teacherPreviewContent = new LinearLayout(this);
        teacherPreviewContent.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(teacherPreviewContent);
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetLp.gravity = Gravity.BOTTOM;
        int m = dp(16);
        sheetLp.setMargins(m, m, m, m);
        teacherPreviewOverlay.addView(sheet, sheetLp);
        return teacherPreviewOverlay;
    }

    // Shows the teacher's schedule in a SEPARATE overlay that never
    // touches the main grid's view hierarchy -- rebuilding the grid the
    // finger is still resting on mid-gesture was exactly what caused the
    // "flashes then immediately reverts" bug (Android auto-cancels the
    // touch when its view gets detached).
    private void enterTeacherViewTemp(String teacherName) {
        viewingTeacherName = teacherName;
        teacherViewLocked = false;
        teacherPreviewTitle.setText(teacherName + " 선생님 시간표");
        renderTeacherPreview(teacherName);
        teacherPreviewOverlay.setVisibility(View.VISIBLE);
        teacherPreviewOverlay.setAlpha(0f);
        teacherPreviewOverlay.animate().alpha(1f).setDuration(150).start();
        teacherModeIndicator.setText(teacherName + " 선생님 시간표 보는 중 -- 위로 밀면 고정");
        teacherModeIndicator.setVisibility(View.VISIBLE);
    }

    private void lockTeacherView() {
        teacherViewLocked = true;
        teacherModeIndicator.setText("\ud83d\udd12 " + viewingTeacherName + " 선생님 시간표 고정됨 -- 탭하면 해제");
    }

    private void exitTeacherView() {
        if (viewingTeacherName == null) return;
        viewingTeacherName = null;
        teacherViewLocked = false;
        teacherModeIndicator.setVisibility(View.GONE);
        teacherPreviewOverlay.animate().alpha(0f).setDuration(120)
                .withEndAction(() -> teacherPreviewOverlay.setVisibility(View.GONE)).start();
    }

    private void renderTeacherPreview(String teacherName) {
        teacherPreviewContent.removeAllViews();
        for (WeekSection ws : weekSections) {
            List<Timetable.TeacherPeriodEntry> all = ws.tt.getTeacherWeek(teacherName);
            LinearLayout weekBlock = new LinearLayout(this);
            weekBlock.setOrientation(LinearLayout.VERTICAL);
            weekBlock.setPadding(0, dp(4), 0, dp(12));

            TableLayout grid = new TableLayout(this);
            grid.setStretchAllColumns(false);
            for (int c = 1; c <= 5; c++) grid.setColumnStretchable(c, true);
            TableRow header = new TableRow(this);
            header.addView(gridHeaderCell(""));
            for (int d = 1; d <= 5; d++) header.addView(gridHeaderCell(DOW_SHORT[d]));
            grid.addView(header);

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
                grid.addView(row);
            }
            weekBlock.addView(grid);
            teacherPreviewContent.addView(weekBlock);
        }
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
                row.animate().alpha(1f).translationX(0f).setStartDelay(i * 40L).setDuration(280)
                        .setInterpolator(new OvershootInterpolator(2f)).start();
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
        fieldsCard.addView(eyebrow("학년 / 반"));
        LinearLayout gradeRow = new LinearLayout(this);
        gradeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout gradeCol = new LinearLayout(this);
        gradeCol.setOrientation(LinearLayout.VERTICAL);
        gradeCol.addView(fieldLabel("학년"));
        gradeAuto = makePickerInput(new String[]{"1", "2", "3", "4", "5", "6"});
        gradeCol.addView(gradeAuto);
        LinearLayout classCol = new LinearLayout(this);
        classCol.setOrientation(LinearLayout.VERTICAL);
        classCol.addView(fieldLabel("반"));
        String[] classOptions = new String[20];
        for (int i = 0; i < 20; i++) classOptions[i] = String.valueOf(i + 1);
        classAuto = makePickerInput(classOptions);
        classCol.addView(classAuto);
        LinearLayout.LayoutParams gradeLp = weightedWrap();
        gradeLp.rightMargin = dp(8);
        gradeRow.addView(gradeCol, gradeLp);
        gradeRow.addView(classCol, weightedWrap());
        fieldsCard.addView(gradeRow);
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

        LinearLayout colorsCard = card();
        colorsCard.addView(eyebrow("과목 색상"));
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
        try {
            int g = Integer.parseInt(gradeAuto.getText().toString().trim());
            int c = Integer.parseInt(classAuto.getText().toString().trim());
            prefs.setClass(g, c);
        } catch (Exception ignored) {}
        NotificationScheduler.rescheduleAll(this);
        if (prefs.liveNotify()) startForegroundService(new Intent(this, LiveNotifyService.class));
        Toast.makeText(this, "적용했어요.", Toast.LENGTH_SHORT).show();
        showPage(0);
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
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(12);
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
        gradeAuto.setText(String.valueOf(prefs.grade()));
        classAuto.setText(String.valueOf(prefs.classNum()));
        refreshSavedClassesList();

        notifyChangeCheck.setChecked(prefs.notifyChange());
        notifyPeriodCheck.setChecked(prefs.notifyPeriod());
        notifyMorningCheck.setChecked(prefs.notifyMorning());
        liveNotifyCheck.setChecked(prefs.liveNotify());
        morningTimeInput.setText(prefs.morningTime());
        for (int i = 0; i < 8; i++) periodInputs[i].setText(prefs.periodTime(i + 1));
        neisKeyInput.setText(prefs.neisApiKey());
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightedWrap() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) { return UiKit.dp(v); }
}
