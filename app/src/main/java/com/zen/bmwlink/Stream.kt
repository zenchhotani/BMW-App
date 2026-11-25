package com.zen.bmwlink

import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

const val MQTT_HOST = "customer.streaming-cardata.bmwgroup.com"
const val MQTT_PORT = 9000

data class Reading(val key: String, val value: String, val unit: String?, val time: String?) {
    fun pretty() = value + (unit?.let { " $it" } ?: "")
}

/** Parses a CarData stream message. Tolerates both {"data":{key:{value,unit,timestamp}}} and flat shapes. */
fun parseMessage(bytes: ByteArray): Map<String, Reading> {
    val root = JSONObject(String(bytes))
    val dataObj = root.optJSONObject("data")
    val src = dataObj ?: root
    val skip = setOf("vin", "topic", "timestamp", "messageid", "gcid")
    val out = mutableMapOf<String, Reading>()
    for (k in src.keys()) {
        if (dataObj == null && k.lowercase() in skip) continue
        val v = src.get(k)
        when (v) {
            is JSONObject -> out[k] = Reading(
                k,
                v.opt("value")?.toString() ?: v.toString(),
                v.optString("unit").ifEmpty { null },
                v.optString("timestamp").ifEmpty { null }
            )
            is JSONArray -> Unit
            else -> out[k] = Reading(k, v.toString(), null, null)
        }
    }
    return out
}

/** MQTT 5 connection to BMW's CarData streaming broker. */
class Stream {
    private var client: Mqtt5AsyncClient? = null

    /** Blocking; call from a background dispatcher. */
    fun connect(gcid: String, idToken: String, vin: String, onData: (Map<String, Reading>) -> Unit) {
        disconnect()
        val c = Mqtt5Client.builder()
            .identifier("bmwlink-" + UUID.randomUUID())
            .serverHost(MQTT_HOST)
            .serverPort(MQTT_PORT)
            .sslWithDefaultConfig()
            .simpleAuth()
            .username(gcid)
            .password(idToken.toByteArray())
            .applySimpleAuth()
            .buildAsync()
        c.connectWith().keepAlive(30).cleanStart(true).send().get(20, TimeUnit.SECONDS)
        c.subscribeWith()
            .topicFilter("$gcid/$vin")
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { pub -> runCatching { onData(parseMessage(pub.payloadAsBytes)) } }
            .send()
            .get(20, TimeUnit.SECONDS)
        client = c
    }

    fun disconnect() {
        runCatching { client?.disconnect() }
        client = null
    }
}
