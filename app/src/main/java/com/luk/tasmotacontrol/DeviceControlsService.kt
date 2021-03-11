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
import io.reactivex.Flowable
import io.reactivex.processors.ReplayProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.*
import okio.IOException
import org.json.JSONObject
import org.reactivestreams.FlowAdapters
import java.util.concurrent.Flow
import java.util.function.Consumer
import kotlin.coroutines.CoroutineContext

class DeviceControlsService : ControlsProviderService(), CoroutineScope {

    private lateinit var controls: HashMap<String, ControlContainer>

    private lateinit var updatePublisher: ReplayProcessor<Control>

    private var httpClient = OkHttpClient()

    private var job: Job = Job()
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.IO

    data class ControlContainer(
        val control: Control,
        val tasmotaId: String,
        val tasmotaToggleUrl: String
    )

    override fun createPublisherForAllAvailable(): Flow.Publisher<Control> {
        createDefaultControls()

        return FlowAdapters.toFlowPublisher(Flowable.fromIterable(controls.map { it.value.control }))
    }

    override fun performControlAction(
        controlId: String,
        action: ControlAction,
        consumer: Consumer<Int>
    ) {
        createDefaultControls()

        val control: ControlContainer? = controls[controlId]

        if (control != null) {
            httpClient.newCall(
                Request.Builder()
                    .url(control.tasmotaToggleUrl)
                    .build()
            ).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    consumer.accept(ControlAction.RESPONSE_FAIL)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        consumer.accept(ControlAction.RESPONSE_FAIL)
                        return
                    }

                    response.body?.use {
                        val json = JSONObject(it.string())

                        consumer.accept(ControlAction.RESPONSE_OK)
                        updatePublisher.onNext(
                            Control.StatefulBuilder(control.control)
                                .setStatus(Control.STATUS_OK)
                                .setControlTemplate(createToggleTemplate(json[control.tasmotaId] == "ON"))
                                .build()
                        )
                    }
                }
            })
        }
    }

    override fun createPublisherFor(list: MutableList<String>): Flow.Publisher<Control> {
        createDefaultControls()

        updatePublisher = ReplayProcessor.create()

        launch {
            var response: JSONObject? = null

            try {
                httpClient.newCall(
                    Request.Builder()
                        .url(URL_POWER)
                        .build()
                ).execute().use {
                    it.body?.use { body ->
                        response = JSONObject(body.string())
                    }
                    it.close()
                }
            } catch (e: Exception) {
                // sad :(
            }

            list.forEach {
                controls[it]?.let { control ->
                    val status =
                        if (response != null) Control.STATUS_OK else Control.STATUS_NOT_FOUND
                    val isOn = response != null && response!![control.tasmotaId] == "ON"

                    updatePublisher.onNext(
                        Control.StatefulBuilder(control.control)
                            .setStatus(status)
                            .setControlTemplate(createToggleTemplate(isOn))
                            .build()
                    )
                }
            }
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

        controls = hashMapOf(
            CONTROL_ID_LIGHT to ControlContainer(
                Control.StatelessBuilder(CONTROL_ID_LIGHT, pendingIntent)
                    .setTitle(getString(R.string.tasmota_light))
                    .setDeviceType(DeviceTypes.TYPE_LIGHT)
                    .build(),
                POWER_LIGHT,
                URL_LIGHT_TOGGLE
            ),
            CONTROL_ID_SPEAKERS to ControlContainer(
                Control.StatelessBuilder(CONTROL_ID_SPEAKERS, pendingIntent)
                    .setTitle(getString(R.string.tasmota_speakers))
                    .setDeviceType(DeviceTypes.TYPE_GENERIC_ON_OFF)
                    .build(),
                POWER_SPEAKERS,
                URL_SPEAKER_TOGGLE
            )
        )
    }

    private fun createToggleTemplate(on: Boolean): ToggleTemplate {
        return ToggleTemplate(
            TEMPLATE_ID_TOGGLE,
            ControlButton(on, getString(if (on) R.string.on else R.string.off))
        )
    }

    companion object {
        private const val CONTROL_ID_LIGHT = "TASMOTA_LIGHT"
        private const val CONTROL_ID_SPEAKERS = "TASMOTA_SPEAKER"

        private const val TEMPLATE_ID_TOGGLE = "TEMPLATE_TOGGLE"

        private const val POWER_ALL = "POWER0"
        private const val POWER_LIGHT = "POWER1"
        private const val POWER_SPEAKERS = "POWER2"

        private const val URL_BASE = "http://192.168.1.225"
        private const val URL_POWER = "${URL_BASE}/cm?cmnd=${POWER_ALL}"
        private const val URL_LIGHT_OFF = "${URL_BASE}/cm?cmnd=${POWER_LIGHT}%200"
        private const val URL_LIGHT_ON = "${URL_BASE}/cm?cmnd=${POWER_LIGHT}%201"
        private const val URL_LIGHT_TOGGLE = "${URL_BASE}/cm?cmnd=${POWER_LIGHT}%202"
        private const val URL_SPEAKER_OFF = "${URL_BASE}/cm?cmnd=${POWER_SPEAKERS}%200"
        private const val URL_SPEAKER_ON = "${URL_BASE}/cm?cmnd=${POWER_SPEAKERS}%201"
        private const val URL_SPEAKER_TOGGLE = "${URL_BASE}/cm?cmnd=${POWER_SPEAKERS}%202"
    }
}
