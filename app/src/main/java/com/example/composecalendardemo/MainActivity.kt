package com.example.composecalendardemo

import android.icu.text.DateFormatSymbols
import android.icu.util.Calendar
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import com.example.composecalendardemo.ui.theme.ComposeCalendarDemoTheme
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 默认周日历，下拉月日历，上滑周日历
 * 月日历左右切换，日期为上/下个月x号
 * 周日历左右切换，日期为上/下周星期x
 * 日历范围：2020年1月1日-当前月之后三个月的那个月份的最后一天
 */
class MainActivity : ComponentActivity() {

    //今天的0点
    private var todayTimestamp = Calendar.getInstance().run {
        getDateBeginTimeInMillis(timeInMillis)
    }
    private var selectTimestamp = mutableStateOf(todayTimestamp)

    //2020-01-01 00:00:00
    private val firstDayTimestamp = 1577808000000L
    private val finalDayTimestamp = Calendar.getInstance().apply {
        //4个月后的第0天就是三个月后的最后一天
        add(Calendar.MONTH, 4)
        set(Calendar.DAY_OF_MONTH, 0)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ComposeCalendarDemoTheme {
                CalendarCompose()
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun CalendarCompose() {
        ConstraintLayout(
            modifier = Modifier
                .fillMaxSize()
                .background(color = Color(0xFFFFFFFF))
        ) {
            val (status, month, calendar) = createRefs()
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .constrainAs(status) {
                        top.linkTo(parent.top)
                    })
            Text(text = DateUtils.formatDateTime(
                this@MainActivity,
                selectTimestamp.value,
                DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_NO_MONTH_DAY
            ),
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .constrainAs(month) {
                        top.linkTo(status.bottom)
                        start.linkTo(parent.start)
                        end.linkTo(parent.end)
                    })
            val scroll = rememberScrollState()
            CalendarView(modifier = Modifier
                .fillMaxWidth()
                .constrainAs(calendar) {
                    top.linkTo(month.bottom)
                    bottom.linkTo(parent.bottom)
                    height = Dimension.fillToConstraints
                }
                .background(color = Color(0xFFFFFFFF)),
                state = scroll) {
                CompositionLocalProvider(
                    LocalOverscrollConfiguration provides null
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .background(Color(0xFFF8F8F8))
                            .padding(bottom = 15.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        repeat(10) {
                            Text(text = "this is text ", modifier = Modifier.height(50.dp))
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun CalendarView(
        modifier: Modifier = Modifier,
        state: ScrollState,
        content: @Composable BoxScope.() -> Unit
    ) {
        val calendarHeightPx = with(LocalDensity.current) { 220.dp.toPx() }
        val columnHeightPx = with(LocalDensity.current) { 44.dp.toPx() }.roundToInt()
        ConstraintLayout(modifier = modifier) {
            val (weekTitle, calendar, con, today) = createRefs()

            val basicState = remember { mutableStateOf(0f) }
            val offsetY = remember { mutableStateOf(0f) }
            val lastTime = remember { mutableStateOf(0L) }
            val lastShowMonth = remember {
                mutableStateOf(false)
            }
            val showMonth = remember {
                mutableStateOf(false)
            }
            //只有在展开收起动画时，才需要offset
            val offset = remember {
                derivedStateOf {
                    if (lastTime.value != 0L) {
                        if (!lastShowMonth.value && offsetY.value >= 0) (offsetY.value - 5 * columnHeightPx).roundToInt()
                            .coerceAtMost(0)
                            .coerceAtLeast(-5 * columnHeightPx)
                        else if (lastShowMonth.value && offsetY.value <= 0) offsetY.value.roundToInt()
                            .coerceAtMost(0)
                            .coerceAtLeast(-5 * columnHeightPx) else 0
                    } else {
                        if (showMonth.value) 0 else -5 * columnHeightPx
                    }
                }
            }
            val onNewDelta: (Float) -> Float = { delta ->
                val oldState = basicState.value
                val newState = (basicState.value + delta).coerceIn(
                    if (lastShowMonth.value) -calendarHeightPx else 0f,
                    if (lastShowMonth.value) 0f else calendarHeightPx
                )
                basicState.value = newState
                if ((System.currentTimeMillis() - lastTime.value) > 50) {
                    if (lastTime.value == 0L) {
                        if ((lastShowMonth.value && basicState.value < 0) || (!lastShowMonth.value && basicState.value > 0)) {
                            showMonth.value = true
                        }
                    }
                    offsetY.value = basicState.value
                    lastTime.value = System.currentTimeMillis()
                }
                newState - oldState
            }
            val nestedScrollConnection = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(
                        available: Offset,
                        source: NestedScrollSource
                    ): Offset {
                        if (source == NestedScrollSource.Drag && (lastTime.value != 0L || (!lastShowMonth.value && available.y > 0 && state.value == 0) || (lastShowMonth.value && available.y < 0))) {
                            val weConsumed = onNewDelta(available.y)
                            return Offset(x = 0f, y = weConsumed)
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (lastTime.value != 0L) {
                            if (available.y != 0f) {
                                showMonth.value = available.y > 0
                            } else if (offsetY.value != 0f) {
                                showMonth.value = offsetY.value > 0
                            }
                            lastShowMonth.value = showMonth.value
                            basicState.value = 0f
                            lastTime.value = 0L
                            return Velocity(0.0f, available.y)
                        }
                        return Velocity.Zero
                    }
                }
            }

            Box(modifier = Modifier
                .fillMaxWidth()
                .constrainAs(calendar) {
                    top.linkTo(weekTitle.bottom)
                }) {
                val monthPagerState = rememberPagerState()
                val weekPagerState = rememberPagerState()
                val selectCalendar = remember {
                    mutableStateOf(Calendar.getInstance().apply {
                        timeInMillis = selectTimestamp.value
                    })
                }
                //当月第几周，用于月向上滑动转为周时使用
                var fixWeek by remember {
                    mutableStateOf(selectCalendar.value.get(Calendar.WEEK_OF_MONTH))
                }
                LaunchedEffect(key1 = selectTimestamp.value) {
                    val c = Calendar.getInstance().apply {
                        timeInMillis = selectTimestamp.value
                    }
                    var day = 3
                    day += c.get(Calendar.DAY_OF_YEAR)
                    val year = c.get(Calendar.YEAR)
                    for (i in 2020 until year) {
                        c.set(Calendar.YEAR, i)
                        day += c.getActualMaximum(Calendar.DAY_OF_YEAR)
                    }
                    //向上取整第几周
                    val weekCur =
                        (ceil(day * 1.0f / 7).toInt() - 1).coerceAtLeast(0)
                    if (weekCur != weekPagerState.currentPage) {
                        weekPagerState.scrollToPage(weekCur)
                    }
                }
                LaunchedEffect(key1 = selectTimestamp.value) {
                    selectCalendar.value = Calendar.getInstance().apply {
                        timeInMillis = selectTimestamp.value
                    }
                    val monthCur =
                        ((selectCalendar.value.get(Calendar.YEAR) - 2020) * 12 + selectCalendar.value.get(
                            Calendar.MONTH
                        )).coerceAtLeast(
                            0
                        )
                    if (monthCur != monthPagerState.currentPage) {
                        monthPagerState.scrollToPage(monthCur)
                    }
                    fixWeek = selectCalendar.value.get(Calendar.WEEK_OF_MONTH)
                }

                //-----以下是监听月pager和周pager currentPage部分
                LaunchedEffect(monthPagerState) {
                    snapshotFlow { monthPagerState.currentPage }.collect { page ->
                        val cur =
                            (selectCalendar.value.get(Calendar.YEAR) - 2020) * 12 + selectCalendar.value.get(
                                Calendar.MONTH
                            )
                        //只有HorizontalPager滑动导致的页数变动的情况下需要自动改变selectTimestamp的值同时更新数据，
                        //其余情况都是selectTimestamp值改变导致的页数变动，不需要再更新selectTimestamp值和更新数据
                        if (page != cur) {
                            selectCalendar.value.add(Calendar.MONTH, page - cur)
                            selectTimestamp.value = selectCalendar.value.timeInMillis
                        }
                    }
                }
                //月日历使用
                val nowCalendar = Calendar.getInstance()
                //周日历使用
                val weekAppend = remember {
                    derivedStateOf { offset.value < (1 - fixWeek) * columnHeightPx }
                }
                val weekHeight = remember {
                    derivedStateOf { if (!showMonth.value || weekAppend.value) 44.dp else 1.dp }
                }
                //1577548800，第一周的第一天，2019-12-29，开头有2019年的3天
                var day = 3
                val weekCalendar = Calendar.getInstance()
                //4个月后的第0天就是三个月后的最后一天
                weekCalendar.add(Calendar.MONTH, 4)
                weekCalendar.set(Calendar.DAY_OF_MONTH, 0)
                day += weekCalendar.get(Calendar.DAY_OF_YEAR)
                val year = weekCalendar.get(Calendar.YEAR)
                for (i in 2020 until year) {
                    weekCalendar.set(Calendar.YEAR, i)
                    day += weekCalendar.getActualMaximum(Calendar.DAY_OF_YEAR)
                }
                val weekTotal = ceil(day * 1.0f / 7).toInt()
                LaunchedEffect(weekPagerState) {
                    snapshotFlow { weekPagerState.currentPage }.collect { page ->
                        val c = Calendar.getInstance().apply {
                            timeInMillis = selectTimestamp.value
                        }
                        var day1 = 3
                        day1 += c.get(Calendar.DAY_OF_YEAR)
                        val year1 = c.get(Calendar.YEAR)
                        for (i in 2020 until year1) {
                            c.set(Calendar.YEAR, i)
                            day1 += c.getActualMaximum(Calendar.DAY_OF_YEAR)
                        }
                        val cur = ceil(day1 * 1.0f / 7).toInt() - 1
                        //只有HorizontalPager滑动导致的页数变动的情况下需要自动改变selectTimestamp的值同时更新数据，
                        //其余情况都是selectTimestamp值改变导致的页数变动，不需要再更新selectTimestamp值和更新数据
                        if (page != cur) {
                            val curMonth = selectCalendar.value.get(Calendar.MONTH)
                            selectCalendar.value.add(Calendar.DATE, 7 * (page - cur))
                            val selMonth = selectCalendar.value.get(Calendar.MONTH)
                            //第一周firstDayTimestamp之前的日子不可选择
                            if (page == 0 && selectCalendar.value.timeInMillis < firstDayTimestamp) {
                                selectCalendar.value.timeInMillis = firstDayTimestamp
                            }
                            //最后一周finalDayTimestamp之后的日子不可选择
                            if (page == weekTotal - 1 && selectCalendar.value.timeInMillis > finalDayTimestamp) {
                                selectCalendar.value.timeInMillis = finalDayTimestamp
                            }
                            selectTimestamp.value = selectCalendar.value.timeInMillis
                            //如果是同一个月，则日历数据不需要更新
                        }
                    }
                }

                //月日历
                HorizontalPager(
                    pageCount = (nowCalendar.get(Calendar.YEAR) - 2020) * 12 + nowCalendar.get(
                        Calendar.MONTH
                    ) + 4,
                    state = monthPagerState,
                    modifier = Modifier
                        .fillMaxWidth(),
                ) { page ->
                    val pageCalendar = Calendar.getInstance().apply {
                        set(Calendar.YEAR, 2020)
                        set(Calendar.MONTH, 0)
                        set(Calendar.DAY_OF_MONTH, 1)
                    }
                    pageCalendar.timeInMillis =
                        getDateBeginTimeInMillis(pageCalendar.timeInMillis)
                    pageCalendar.add(Calendar.MONTH, page)
                    val todayDayOfWeek = pageCalendar.get(Calendar.DAY_OF_WEEK)
                    pageCalendar.add(Calendar.DATE, -todayDayOfWeek + 1)//走到当前周的第一天
                    ConstraintLayout(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(LocalDensity.current) { (6 * columnHeightPx + offset.value).toDp() })
                            .offset { IntOffset(0, offset.value) }
                    ) {
                        val (w1, w2, w3, w4, w5, w6) = createRefs()
                        CalendarWeekView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .constrainAs(w1) {
                                    top.linkTo(parent.top)
                                },
                            time = pageCalendar.timeInMillis,
                            greyDay = if (todayDayOfWeek == 1) 0 else -1
                        )
                        pageCalendar.add(Calendar.DATE, 7)
                        CalendarWeekView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .constrainAs(w2) {
                                    top.linkTo(parent.top, margin = 44.dp)
                                },
                            time = pageCalendar.timeInMillis,
                            greyDay = 0
                        )
                        pageCalendar.add(Calendar.DATE, 7)
                        CalendarWeekView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .constrainAs(w3) {
                                    top.linkTo(parent.top, margin = 88.dp)
                                },
                            time = pageCalendar.timeInMillis,
                            greyDay = 0
                        )
                        pageCalendar.add(Calendar.DATE, 7)
                        CalendarWeekView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .constrainAs(w4) {
                                    top.linkTo(parent.top, margin = 132.dp)
                                },
                            time = pageCalendar.timeInMillis,
                            greyDay = 0
                        )
                        pageCalendar.add(Calendar.DATE, 7)
                        CalendarWeekView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .constrainAs(w5) {
                                    top.linkTo(parent.top, margin = 176.dp)
                                },
                            time = pageCalendar.timeInMillis,
                            greyDay = 1
                        )
                        pageCalendar.add(Calendar.DATE, 7)
                        CalendarWeekView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .constrainAs(w6) {
                                    top.linkTo(parent.top, margin = 220.dp)
                                },
                            time = pageCalendar.timeInMillis,
                            greyDay = 1
                        )
                    }
                }
                //周日历
                HorizontalPager(
                    pageCount = weekTotal,
                    state = weekPagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(weekHeight.value),
                ) { page ->
                    val pageCalendar = Calendar.getInstance().apply {
                        set(Calendar.YEAR, 2019)
                        set(Calendar.MONTH, 11)
                        set(Calendar.DAY_OF_MONTH, 29)
                    }
                    pageCalendar.add(Calendar.DATE, 7 * page)
                    pageCalendar.timeInMillis =
                        getDateBeginTimeInMillis(pageCalendar.timeInMillis)
                    CalendarWeekView(
                        modifier = Modifier.fillMaxWidth(),
                        time = pageCalendar.timeInMillis,
                        greyDay = if (page == 0) -1 else if (page == weekTotal - 1) 1 else 0
                    )
                }
            }
            Row(modifier = Modifier
                .fillMaxWidth()
                .constrainAs(weekTitle) {
                    top.linkTo(parent.top)
                }) {
                //星期文案，数组长度为8，不受firstDayOfWeek影响
                val week = DateFormatSymbols.getInstance().shortWeekdays
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .background(color = Color(0xFFFFFFFF))
                        .weight(1f), contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = week[Calendar.SUNDAY],
                    )
                }
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .background(color = Color(0xFFFFFFFF))
                        .weight(1f), contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = week[Calendar.MONDAY],
                    )
                }
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .background(color = Color(0xFFFFFFFF))
                        .weight(1f), contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = week[Calendar.TUESDAY],
                    )
                }
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .background(color = Color(0xFFFFFFFF))
                        .weight(1f), contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = week[Calendar.WEDNESDAY],
                    )
                }
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .background(color = Color(0xFFFFFFFF))
                        .weight(1f), contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = week[Calendar.THURSDAY],
                    )
                }
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .background(color = Color(0xFFFFFFFF))
                        .weight(1f), contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = week[Calendar.FRIDAY],
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(color = Color(0xFFFFFFFF))
                        .height(40.dp)
                        .background(color = Color(0xFFFFFFFF))
                        .weight(1f), contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = week[Calendar.SATURDAY],
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(nestedScrollConnection)
                    .constrainAs(con) {
                        top.linkTo(calendar.bottom, margin = 12.dp)
                        bottom.linkTo(parent.bottom)
                        height = Dimension.fillToConstraints
                    },
                content = content
            )
        }
    }

    /**
     * 一行展示一周日期，组件总高度44.dp，文本item高度40.dp
     * @param time 这周第一天，即周日的时间戳
     * @param greyDay 非本月的日子，显示灰色，-1表示该周前面存在非本月的日子，0表示这周不存在非本月的日子，1表示该周后面存在非本月的日子
     */
    @Composable
    fun CalendarWeekView(
        modifier: Modifier = Modifier,
        time: Long,
        greyDay: Int
    ) {
        Row(
            modifier = modifier
                .background(color = Color(0xFFFFFFFF))
                .padding(vertical = 2.dp)
        ) {
            val calendar = Calendar.getInstance().apply { timeInMillis = time }
            val day1 = calendar.get(Calendar.DAY_OF_MONTH)
            calendar.add(Calendar.DATE, 1)
            val time2 = calendar.timeInMillis
            val day2 = calendar.get(Calendar.DAY_OF_MONTH)
            calendar.add(Calendar.DATE, 1)
            val time3 = calendar.timeInMillis
            val day3 = calendar.get(Calendar.DAY_OF_MONTH)
            calendar.add(Calendar.DATE, 1)
            val time4 = calendar.timeInMillis
            val day4 = calendar.get(Calendar.DAY_OF_MONTH)
            calendar.add(Calendar.DATE, 1)
            val time5 = calendar.timeInMillis
            val day5 = calendar.get(Calendar.DAY_OF_MONTH)
            calendar.add(Calendar.DATE, 1)
            val time6 = calendar.timeInMillis
            val day6 = calendar.get(Calendar.DAY_OF_MONTH)
            calendar.add(Calendar.DATE, 1)
            val time7 = calendar.timeInMillis
            val day7 = calendar.get(Calendar.DAY_OF_MONTH)

            CalendarDayView(
                modifier = Modifier
                    .height(40.dp)
                    .weight(1f),
                title = day1.toString(),
                time = time,
                grey = (greyDay < 0 && day1 > 7) || (greyDay > 0 && day1 < 15)

            )
            CalendarDayView(
                modifier = Modifier
                    .height(40.dp)
                    .weight(1f),
                title = day2.toString(),
                time = time2,
                grey = (greyDay < 0 && day2 > 7) || (greyDay > 0 && day2 < 15)
            )
            CalendarDayView(
                modifier = Modifier
                    .height(40.dp)
                    .weight(1f),
                title = day3.toString(),
                time = time3,
                grey = (greyDay < 0 && day3 > 7) || (greyDay > 0 && day3 < 15)
            )
            CalendarDayView(
                modifier = Modifier
                    .height(40.dp)
                    .weight(1f),
                title = day4.toString(),
                time = time4,
                grey = (greyDay < 0 && day4 > 7) || (greyDay > 0 && day4 < 15)
            )
            CalendarDayView(
                modifier = Modifier
                    .height(40.dp)
                    .weight(1f),
                title = day5.toString(),
                time = time5,
                grey = (greyDay < 0 && day5 > 7) || (greyDay > 0 && day5 < 15)
            )
            CalendarDayView(
                modifier = Modifier
                    .height(40.dp)
                    .weight(1f),
                title = day6.toString(),
                time = time6,
                grey = (greyDay < 0 && day6 > 7) || (greyDay > 0 && day6 < 15)
            )
            CalendarDayView(
                modifier = Modifier
                    .height(40.dp)
                    .weight(1f),
                title = day7.toString(),
                time = time7,
                grey = (greyDay < 0 && day7 > 7) || (greyDay > 0 && day7 < 15)
            )
        }
    }

    /**
     * 日历中的每一天
     */
    @Composable
    fun CalendarDayView(
        modifier: Modifier = Modifier,
        title: String,
        time: Long,
        grey: Boolean,
    ) {
        ConstraintLayout(modifier = modifier.clickable {
            //第一周firstDayTimestamp之前的日子和最后一周finalDayTimestamp之后的日子不可点击
            if (!grey || (time in firstDayTimestamp..finalDayTimestamp)) {
                selectTimestamp.value = time
            }
        }) {
            val (bg, text) = createRefs()

            //选中状态有背景，非未来日子是绿色背景，未来日子是灰色背景
            if (selectTimestamp.value == time) {
                Box(modifier = Modifier
                    .size(38.dp)
                    .background(
                        color = Color.Green,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .constrainAs(bg) {
                        start.linkTo(parent.start)
                        end.linkTo(parent.end)
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                    })
            }
            Text(text = title,
                color = if (grey) Color.Gray else Color.Black,
                modifier = Modifier.constrainAs(text) {
                    start.linkTo(parent.start)
                    end.linkTo(parent.end)
                    top.linkTo(parent.top)
                    bottom.linkTo(parent.bottom)
                })
        }
    }

    @Composable
    fun CalendarProgress(
        modifier: Modifier = Modifier,
        progress: Float,
        backgroundColor: Color,
        progressBgColor: Color,
        progressColor: Color,
    ) {
        Canvas(
            modifier = modifier
                .size(32.dp),
            onDraw = {
                drawArc(
                    color = progressBgColor,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = true
                )
                drawArc(
                    color = progressColor,
                    startAngle = 135f,
                    sweepAngle = 270f * progress,
                    useCenter = true
                )
                drawArc(
                    color = backgroundColor,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = true,
                    topLeft = Offset(x = size.width / 11, y = size.height / 11),
                    size = size.copy(width = size.width / 11 * 9, height = size.height / 11 * 9)
                )
                drawCircle(
                    color = progressBgColor,
                    radius = size.width / 22,
                    center = Offset(
                        x = (size.width / 2 + size.width / 11 * 5 * sin(Math.PI / 4)).toFloat(),
                        y = (size.width / 2 + size.width / 11 * 5 * sin(Math.PI / 4)).toFloat()
                    )
                )
                drawCircle(
                    color = progressColor,
                    radius = size.width / 22,
                    center = Offset(
                        x = (size.width / 2 - size.width / 11 * 5 * sin(Math.PI / 4)).toFloat(),
                        y = (size.width / 2 + size.width / 11 * 5 * sin(Math.PI / 4)).toFloat()
                    )
                )
                drawCircle(
                    color = progressColor,
                    radius = size.width / 22,
                    center = Offset(
                        x = (size.width / 2 - size.width / 11 * 5 * cos(3 * Math.PI * progress / 2 - Math.PI / 4)).toFloat(),
                        y = (size.width / 2 - size.width / 11 * 5 * sin(3 * Math.PI * progress / 2 - Math.PI / 4)).toFloat()
                    )
                )
            }
        )
    }

    /**
     * 当天0点时刻
     */
    fun getDateBeginTimeInMillis(time: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = time
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
}