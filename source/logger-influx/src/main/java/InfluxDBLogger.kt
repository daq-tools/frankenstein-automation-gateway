import at.rocworks.data.Globals
import at.rocworks.data.Topic
import at.rocworks.data.Value
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.servicediscovery.ServiceDiscovery
import org.influxdb.BatchOptions
import org.influxdb.InfluxDB
import org.influxdb.InfluxDBFactory
import org.influxdb.InfluxDBIOException
import org.influxdb.dto.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.logging.Level
import java.util.logging.Logger
import java.util.concurrent.TimeUnit
import java.lang.IllegalStateException
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class InfluxDBLogger(private val config: JsonObject) : AbstractVerticle() {
    private val id = config.getString("Id", "InfluxDB")
    private val logger = LoggerFactory.getLogger(id)

    private val url = config.getString("Url", "")
    private val username = config.getString("Username", "")
    private val password = config.getString("Password", "")
    private val database = config.getString("Database", "scada")

    private val db: InfluxDB

    companion object {
        const val defaultRetryWaitTime = 5000L
    }

    private val writeParameterQueueSize : Int
    private val writeParameterQueueSizeDef = 10000

    init {
        val writeParameters = config.getJsonObject("WriteParameters")
        writeParameterQueueSize = writeParameters?.getInteger("QueueSize", writeParameterQueueSizeDef) ?: writeParameterQueueSizeDef

        Logger.getLogger(id).level = Level.parse(config.getString("LogLevel", "INFO"))
        db = if (username == null || username == "")
            InfluxDBFactory.connect(url)
        else
            InfluxDBFactory.connect(url, username, password)
    }

    override fun start(startPromise: Promise<Void>) {
        fun connect() {
            thread {
                try {
                    val response: Pong = db.ping()
                    if (!response.isGood) {
                        logger.error("InfluxDB connect failed! Wait and retry...")
                        vertx.setTimer(defaultRetryWaitTime) { connect() }
                    } else {
                        logger.info("InfluxDB connected.")
                        db.setLogLevel(InfluxDB.LogLevel.NONE);
                        db.query(Query("CREATE DATABASE $database"))
                        db.setDatabase(database)
                        db.enableBatch(BatchOptions.DEFAULTS) // TODO: make batch options configurable
                        this.subscribeTopics()
                        vertx.setPeriodic(1000, ::metricCalculator)
                        vertx.eventBus().consumer("${Globals.BUS_ROOT_URI_LOG}/$id/QueryHistory", ::queryHandler)
                        startPromise.complete()
                    }
                } catch (e: InfluxDBIOException) {
                    logger.error("InfluxDB connect failed! Wait and retry...[{}]", e.message)
                    vertx.setTimer(defaultRetryWaitTime) { connect() }
                } catch (e: Exception) {
                    e.printStackTrace()
                    startPromise.fail(e)
                }
            }
        }
        connect()
    }

    override fun stop(stopPromise: Promise<Void>) {
        writeValueStop.set(true)
        writeValueStopped.future().onComplete { stopPromise.complete() }
    }


    private fun isServiceAvailable(type: String, name: String): Future<String> {
        val promise = Promise.promise<String>()
        if (type==Topic.SystemType.Mqtt.toString())
            promise.complete("")
        else {
            val discovery = ServiceDiscovery.create(vertx)
            fun discover() {
                discovery.getRecord({ r -> r.name == name && r.type == type }) { ar ->
                    if (ar.succeeded() && ar.result() != null) {
                        logger.info("Service [{}] logging is now available!", ar.result().location)
                        promise.complete(ar.result().location.getString("endpoint"))
                    } else {
                        logger.error("Lookup service [{}/{}] failed! Wait and retry...", type, name)
                        vertx.setTimer(defaultRetryWaitTime) { discover() }
                    }
                }
            }
            discover()
        }
        return promise.future()
    }

    private fun subscribeTopics() {
        config.getJsonArray("Logging")
            ?.filterIsInstance<JsonObject>()
            ?.forEach { it ->
                val topic = Topic.parseTopic(it.getString("Topic", ""))
                if (topic.format == Topic.Format.Json) {
                    // TODO: optimize service lookup, don't do it for every single topic
                    isServiceAvailable(topic.systemType.toString(), topic.systemName).onComplete { endpoint ->
                        subscribeTopic(endpoint.result(), topic)
                    }
                } else {
                    val message = "Invalid topic: " + topic.topicName
                    logger.warn(message)
                }
            }
    }

    private fun subscribeTopic(endpoint: String, topic: Topic) {
        val consumer = vertx.eventBus().consumer<Any>(topic.topicName) { valueConsumer(it.body()) }
        val request = JsonObject().put("ClientId", this.id).put("Topic", topic.encodeToJson())
        if (endpoint!="") {
            vertx.eventBus().request<JsonObject>("${endpoint}/Subscribe", request) {
                logger.debug("Subscribe response [{}] [{}]", it.succeeded(), it.result()?.body())
                if (!(it.succeeded() && it.result().body().getBoolean("Ok"))) {
                    consumer.unregister()
                }
            }
        }
    }

    private fun valueConsumer(value: Any) {
        try {
            when (value) {
                is Buffer -> valueConsumer(Json.decodeValue(value) as JsonObject)
                is JsonObject -> valueConsumer(value)
                else -> logger.warn("Got unhandled class of instance []", value.javaClass.simpleName)
            }
        } catch (e: Exception) {
            logger.error(e.message)
        }
    }

    private val writeValueStop = AtomicBoolean(false)
    private val writeValueStopped = Promise.promise<Boolean>()
    private val writeValueQueue = ArrayBlockingQueue<Point>(writeParameterQueueSize)
    private var writeValueQueueFull = false
    private val writeValueThread =
        thread {
            logger.info("Writer thread with queue size [{}]", writeValueQueue.remainingCapacity())
            var point : Point? = null
            while (!writeValueStop.get()) {
                point = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
                while (point!=null) {
                    db.write(point)
                    valueCounterOutput++
                    point = writeValueQueue.poll()
                }
            }
            writeValueStopped.complete()
        }


    var valueCounterInput : Int = 0
    @Volatile var valueCounterOutput : Int = 0

    private fun valueConsumer(data: JsonObject) {
        valueCounterInput++
        try {
            val topic = Topic.decodeFromJson(data.getJsonObject("Topic"))
            val value = Value.decodeFromJson(data.getJsonObject("Value"))
            if (value.value == null) return
            val numeric: Double? = (value.value as String).toDoubleOrNull()
            val point = Point.measurement(topic.systemName)
                .time(value.serverTime.toEpochMilli(), TimeUnit.MILLISECONDS)
                .tag("tag", topic.topicInfo)
                .tag("system", topic.systemName)
                .tag("status", value.statusCode.toString())
            if (numeric != null)
                point.addField("value", numeric)
            else
                point.addField("text", value.value.toString())

            try {
                writeValueQueue.add(point.build())
                if (writeValueQueueFull) {
                    writeValueQueueFull = false
                    logger.warn("Write queue free again! [{}]", writeValueQueue.size)
                }
            } catch (e: IllegalStateException) {
                if (!writeValueQueueFull) {
                    writeValueQueueFull = true
                    logger.warn("Write queue is full! [{}]", writeParameterQueueSize)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var t1: Instant = Instant.now()
    private fun metricCalculator(jobId: Long) {
        val t2 = Instant.now()
        val d = Duration.between(t1, t2).toMillis() / 1000.0
        if (d>0) {
            val topic = "logger/${this.id}/metrics"
            val vsInput = (valueCounterInput / d).roundToInt()
            val vsOutput = (valueCounterOutput / d).roundToInt()
            val result = JsonObject()
            result.put("Input v/s", vsInput)
            result.put("Output v/s", vsOutput)
            vertx.eventBus().publish(topic, result)
        }
        t1 = t2
        valueCounterInput = 0
        valueCounterOutput = 0
    }

    private fun queryHandler(message: Message<JsonObject>) {
        val request = message.body()
        val system = request.getString("System")
        val nodeId = request.getString("NodeId")
        val t1 = request.getLong("T1") * 1000000 // ms to nano
        val t2 = request.getLong("T2") * 1000000 // ms to nano

        try {
            if (db != null) {
                val sql = "SELECT value, status, system FROM \"${system}\" WHERE \"tag\" = '$nodeId' AND time >= $t1 AND time <= $t2"
                val res: QueryResult = db!!.query(Query(sql))
                val list = res.results.getOrNull(0)?.series?.getOrNull(0)?.values
                if (list!=null) message.reply(JsonObject().put("Ok", true).put("Result", list))
                else message.reply(JsonObject().put("Ok", false))
            } else {
                message.reply(JsonObject().put("Ok", false))
            }
        } catch (e: Exception) {
            message.reply(JsonObject().put("Ok", false))
            e.printStackTrace()
        }
    }
}