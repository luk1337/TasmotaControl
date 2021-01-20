package com.luk.tasmotacontrol

import android.app.PendingIntent
import android.content.Intent
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.DeviceTypes
import android.service.controls.actions.ControlAction
import android.service.controls.templates.ControlButton
import android.service.controls.templates.ToggleTemplate
import androidx.appcompat.app.AppCompatActivity
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.RequestFuture
import com.android.volley.toolbox.Volley
import io.reactivex.Flowable
import io.reactivex.processors.ReplayProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.reactivestreams.FlowAdapters
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.coroutines.CoroutineContext

class DeviceControlsService : ControlsProviderService(), CoroutineScope {

    lateinit var controlLight: Control
    lateinit var controlSpeakers: Control

    lateinit var updatePublisher: ReplayProcessor<Control>

    var job: Job = Job()
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.IO

    private val requestQueue: RequestQueue by lazy {
        Volley.newRequestQueue(applicationContext)
    }

    override fun createPublisherForAllAvailable(): Flow.Publisher<Control> {
        createDefaultControls()

        val controlList = mutableListOf(
            controlLight,
            controlSpeakers
        )

        return FlowAdapters.toFlowPublisher(Flowable.fromIterable(controlList))
    }

    override fun performControlAction(
        controlId: String,
        action: ControlAction,
        consumer: Consumer<Int>
    ) {
        createDefaultControls()

        if (controlId == CONTROL_ID_LIGHT) {
            requestQueue.add(JsonObjectRequest(
                Request.Method.GET,
                URL_LIGHT_TOGGLE,
                null,
                {
                    consumer.accept(ControlAction.RESPONSE_OK)
                    updatePublisher.onNext(
                        Control.StatefulBuilder(controlLight)
                            .setStatus(Control.STATUS_OK)
                            .setControlTemplate(createToggleTemplate(it[POWER_LIGHT] == "ON"))
                            .build()
                    )
                },
                {
                    consumer.accept(ControlAction.RESPONSE_FAIL)
                }
            ))
        } else if (controlId == CONTROL_ID_SPEAKERS) {
            requestQueue.add(JsonObjectRequest(
                Request.Method.GET,
                URL_SPEAKER_TOGGLE,
                null,
                {
                    consumer.accept(ControlAction.RESPONSE_OK)
                    updatePublisher.onNext(
                        Control.StatefulBuilder(controlSpeakers)
                            .setStatus(Control.STATUS_OK)
                            .setControlTemplate(createToggleTemplate(it[POWER_SPEAKERS] == "ON"))
                            .build()
                    )
                },
                {
                    consumer.accept(ControlAction.RESPONSE_FAIL)
                }
            ))
        }
    }

    override fun createPublisherFor(list: MutableList<String>): Flow.Publisher<Control> {
        createDefaultControls()

        updatePublisher = ReplayProcessor.create()

        launch {
            val future = RequestFuture.newFuture<JSONObject>()
            val request = JsonObjectRequest(Request.Method.GET, URL_POWER, null, future, future)
            requestQueue.add(request)

            var lightOn = false
            var lightStatus = Control.STATUS_NOT_FOUND

            var speakersOn = false
            var speakersStatus = Control.STATUS_NOT_FOUND

            try {
                val response = future.get(5, TimeUnit.SECONDS)

                lightOn = response[POWER_LIGHT] == "ON"
                lightStatus = Control.STATUS_OK

                speakersOn = response[POWER_SPEAKERS] == "ON"
                speakersStatus = Control.STATUS_OK
            } catch (e: Exception) {
                // sad :(
            }

            updatePublisher.onNext(
                Control.StatefulBuilder(controlLight)
                    .setStatus(lightStatus)
                    .setControlTemplate(createToggleTemplate(lightOn))
                    .build()
            )
            updatePublisher.onNext(
                Control.StatefulBuilder(controlSpeakers)
                    .setStatus(speakersStatus)
                    .setControlTemplate(createToggleTemplate(speakersOn))
                    .build()
            )
        }

        return FlowAdapters.toFlowPublisher(updatePublisher)
    }

    private fun createDefaultControls() {
        val intent = Intent(Intent.ACTION_MAIN)
            .setClass(this, AppCompatActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        controlLight = Control.StatelessBuilder(CONTROL_ID_LIGHT, pendingIntent)
            .setTitle(getString(R.string.tasmota_light))
            .setDeviceType(DeviceTypes.TYPE_LIGHT)
            .build()
        controlSpeakers = Control.StatelessBuilder(CONTROL_ID_SPEAKERS, pendingIntent)
            .setTitle(getString(R.string.tasmota_speakers))
            .setDeviceType(DeviceTypes.TYPE_GENERIC_ON_OFF)
            .build()
    }

    private fun createToggleTemplate(on: Boolean): ToggleTemplate {
        return ToggleTemplate(
            TEMPLATE_ID_TOGGLE,
            ControlButton(on, getString(if (on) R.string.on else R.string.off))
        )
    }

    companion object {
        const val CONTROL_ID_LIGHT = "TASMOTA_LIGHT"
        const val CONTROL_ID_SPEAKERS = "TASMOTA_SPEAKER"

        const val TEMPLATE_ID_TOGGLE = "TEMPLATE_TOGGLE"

        const val POWER_ALL = "POWER0"
        const val POWER_LIGHT = "POWER1"
        const val POWER_SPEAKERS = "POWER2"

        const val URL_BASE = "http://192.168.1.225"
        const val URL_POWER = "${URL_BASE}/cm?cmnd=${POWER_ALL}"
        const val URL_LIGHT_OFF = "${URL_BASE}/cm?cmnd=${POWER_LIGHT}%200"
        const val URL_LIGHT_ON = "${URL_BASE}/cm?cmnd=${POWER_LIGHT}%201"
        const val URL_LIGHT_TOGGLE = "${URL_BASE}/cm?cmnd=${POWER_LIGHT}%202"
        const val URL_SPEAKER_OFF = "${URL_BASE}/cm?cmnd=${POWER_SPEAKERS}%200"
        const val URL_SPEAKER_ON = "${URL_BASE}/cm?cmnd=${POWER_SPEAKERS}%201"
        const val URL_SPEAKER_TOGGLE = "${URL_BASE}/cm?cmnd=${POWER_SPEAKERS}%202"
    }
}
