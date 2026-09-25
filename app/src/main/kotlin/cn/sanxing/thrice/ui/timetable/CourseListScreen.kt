package cn.sanxing.thrice.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.CourseKind
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.common.UiUtils
import kotlinx.coroutines.flow.flowOf

/**
 * 全部课程页：按星期分组列出当前学期的所有网格课程 + 底部「其他课程」，
 * 点击任意课程进入编辑页。课表顶部控制栏的「全部」入口进入此页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseListScreen(
    container: AppContainer,
    onClose: () -> Unit,
    onOpenEdit: (Long) -> Unit
) {
    val term by container.termRepository.observeActive().collectAsState(null)
    val courses by (term?.let { container.courseRepository.observeByTerm(it.id) }
        ?: flowOf(emptyList())).collectAsState(emptyList())

    val gridCourses = courses.filter { it.kind == CourseKind.GRID && it.dayOfWeek in 1..7 }
        .sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }, { it.endSection }))
    val otherCourses = courses.filter { it.kind == CourseKind.OTHER || it.dayOfWeek !in 1..7 }
        .sortedBy { it.name }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.all_courses_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpenEdit(-1L) }) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        }
    ) { padding ->
        if (courses.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.all_courses_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (day in 1..7) {
                val dayCourses = gridCourses.filter { it.dayOfWeek == day }
                if (dayCourses.isEmpty()) continue
                item(key = "header-$day") {
                    Text(
                        text = stringResource(UiUtils.dayLabelRes(day)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp)
                    )
                }
                items(dayCourses, key = { it.id }) { course ->
                    CourseListRow(course) { onOpenEdit(course.id) }
                }
            }

            if (otherCourses.isNotEmpty()) {
                item(key = "header-other") {
                    Text(
                        text = stringResource(R.string.all_courses_other),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp)
                    )
                }
                items(otherCourses, key = { "other-${it.id}" }) { course ->
                    CourseListRow(course) { onOpenEdit(course.id) }
                }
            }
            item(key = "bottom-space") { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun CourseListRow(course: Course, onClick: () -> Unit) {
    val color = UiUtils.courseColor(course)
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(shape)
            .background(color.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                course.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            val meta = buildList {
                if (course.kind == CourseKind.GRID && course.startSection > 0) {
                    add(stringResource(R.string.sections_range_format, course.startSection, course.endSection))
                }
                if (course.weeks.isNotEmpty()) add(UiUtils.formatWeeks(course.weeks))
                course.teacher.takeIf { it.isNotBlank() }?.let { add(it) }
                course.location.takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }
    }
}
