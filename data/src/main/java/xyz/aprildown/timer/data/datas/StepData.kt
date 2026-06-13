package xyz.aprildown.timer.data.datas

import androidx.annotation.ColorInt
import androidx.annotation.Keep
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import xyz.aprildown.timer.domain.entities.StepType

@Keep
internal sealed class StepData {

    @Keep
    @JsonClass(generateAdapter = true)
    internal data class Step(
        @Json(name = "label")
        val label: String,

        @Json(name = "length")
        val length: Long,

        @Json(name = "behaviour")
        val behaviour: List<BehaviourData> = emptyList(),

        @Json(name = "type")
        val type: StepType = StepType.NORMAL,

        @Json(name = "color")
        @ColorInt val color: Int? = null
    ) : StepData()

    @Keep
    @JsonClass(generateAdapter = true)
    internal data class Group(
        @Json(name = "name")
        val name: String,

        @Json(name = "loop")
        val loop: Int,

        @Json(name = "steps")
        val steps: List<StepData>
    ) : StepData()
}
