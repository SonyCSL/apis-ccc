package jp.co.sony.csl.dcoes.apis.tools.ccc.impl.mongo_db;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.mongo.MongoClient;
import jp.co.sony.csl.dcoes.apis.common.util.DateTimeUtil;
import jp.co.sony.csl.dcoes.apis.common.util.vertx.JsonObjectUtil;
import jp.co.sony.csl.dcoes.apis.common.util.vertx.VertxConfig;
import jp.co.sony.csl.dcoes.apis.tools.ccc.DeallogAcquisition;

/**
 * Implements direct storing of Power Sharing information in MongoDB.
 * Used in {@link DeallogAcquisition}.
 * @author OES Project
 * MongoDB に対して融通情報を直接保存する実装.
 * {@link DeallogAcquisition} で使用される.
 * @author OES Project
 */
public class MongoDBDeallogAcquisitionImpl implements DeallogAcquisition.Impl {
	private static final Logger log = LoggerFactory.getLogger(MongoDBDeallogAcquisitionImpl.class);

	private static final JsonObjectUtil.DefaultString DEFAULT_HOST = JsonObjectUtil.defaultString("localhost");
	private static final Integer DEFAULT_PORT = Integer.valueOf(27017);
	private static final Boolean DEFAULT_SSL = Boolean.FALSE;
	private static final Boolean DEFAULT_SSL_TRUST_ALL = Boolean.FALSE;

	@SuppressWarnings("unused") private Vertx vertx_;
	private static MongoClient client_ = null;
	private static String collection_ = null;

	/**
	 * Creates instance.
	 * Gets settings from CONFIG and initializes.
	 * - CONFIG.deallogAcquisition.host : Connection destination host name [{@link String}].
	 *                               Default : localhost.
	 * - CONFIG.deallogAcquisition.port : Connection destination port [{@link String}].
	 *                               Default : 27017.
	 * - CONFIG.deallogAcquisition.ssl : SSL flag [{@link Boolean}].
	 *                              Default : false.
	 * - CONFIG.deallogAcquisition.sslTrustAll : OK flag [{@link Boolean}] for any SSL.
	 *                                      Default : false.
	 * - CONFIG.deallogAcquisition.database : Database name [{@link String}].
	 *                                   Required.
	 * - CONFIG.deallogAcquisition.collection : Collection name [{@link String}].
	 *                                     Required.
	 * @param vertx vertx object
	 * インスタンスを作成する.
	 * CONFIG から設定を取得し初期化する.
	 * - CONFIG.deallogAcquisition.host : 接続先ホスト名 [{@link String}].
	 *                               デフォルト : localhost.
	 * - CONFIG.deallogAcquisition.port : 接続先ポート [{@link Integer}].
	 *                               デフォルト : 27017.
	 * - CONFIG.deallogAcquisition.ssl : SSL フラグ [{@link Boolean}].
	 *                              デフォルト : false.
	 * - CONFIG.deallogAcquisition.sslTrustAll : SSL なんでも OK フラグ [{@link Boolean}].
	 *                                      デフォルト : false.
	 * - CONFIG.deallogAcquisition.database : データベース名 [{@link String}].
	 *                                   必須.
	 * - CONFIG.deallogAcquisition.collection : コレクション名 [{@link String}].
	 *                                     必須.
	 * @param vertx vertx オブジェクト
	 */
	public MongoDBDeallogAcquisitionImpl(Vertx vertx) {
		vertx_ = vertx;
		String host = VertxConfig.config.getString(DEFAULT_HOST, "deallogAcquisition", "host");
		Integer port = VertxConfig.config.getInteger(DEFAULT_PORT, "deallogAcquisition", "port");
		Boolean ssl = VertxConfig.config.getBoolean(DEFAULT_SSL, "deallogAcquisition", "ssl");
		Boolean sslTrustAll = VertxConfig.config.getBoolean(DEFAULT_SSL_TRUST_ALL, "deallogAcquisition", "sslTrustAll");
		String database = VertxConfig.config.getString("deallogAcquisition", "database");
		JsonObject config = new JsonObject().put("host", host).put("port", port).put("ssl", ssl).put("db_name", database);
		if (ssl) config.put("trustAll", sslTrustAll);
		client_ = MongoClient.createShared(vertx, config);
		collection_ = VertxConfig.config.getString("deallogAcquisition", "collection");
		if (log.isInfoEnabled()) log.info("host : " + host);
		if (log.isInfoEnabled()) log.info("port : " + port);
		if (log.isInfoEnabled()) log.info("ssl : " + ssl);
		if (ssl) if (log.isInfoEnabled()) log.info("sslTrustAll : " + sslTrustAll);
		if (log.isInfoEnabled()) log.info("database : " + database);
		if (log.isInfoEnabled()) log.info("collection : " + collection_);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override public void report(JsonObject conditions, Handler<AsyncResult<Void>> completionHandler) {
		find_(conditions, completionHandler);
	}

	private void find_(JsonObject conditions, Handler<AsyncResult<Void>> completionHandler) {
		if (conditions.isEmpty()) {
			completionHandler.handle(Future.failedFuture("no conditions"));
		} else {
			// 検索条件（dealに登録されるstopDateTimeはUTCのため合わせる必要あり）
			long acquisitionTimeStartLong = conditions.getLong("acquisitionTimeStart");
			ZonedDateTime acquisitionTimeStartZdt
				= ZonedDateTime
					.ofInstant(Instant.ofEpochMilli(acquisitionTimeStartLong), ZoneId.of("UTC"))
					.minusHours(9);
			log.info(acquisitionTimeStartZdt);
			String acquisitionTimeStart = DateTimeUtil.toISO8601OffsetString(acquisitionTimeStartZdt);

			long acquisitionTimeEndLong = conditions.getLong("acquisitionTimeEnd");
			ZonedDateTime acquisitionTimeEndZdt
				= ZonedDateTime
					.ofInstant(Instant.ofEpochMilli(acquisitionTimeEndLong), ZoneId.of("UTC"))
					.minusHours(9);
			log.info(acquisitionTimeEndZdt);
			String acquisitionTimeEnd = DateTimeUtil.toISO8601OffsetString(acquisitionTimeEndZdt);

			JsonObject query = new JsonObject()
				.put("stopDateTime",
					new JsonObject()
						.put("$gte", new JsonObject().put("$date", acquisitionTimeStart))
						.put("$lt", new JsonObject().put("$date", acquisitionTimeEnd)));
			log.info(query);
			client_.find(collection_, query, res -> {
				if (res.succeeded()) {
					List<JsonObject> results = res.result();
					log.info(results);
					// DateTimeフィールドをUTCからJSTに変換
					convertDateTimeFieldUtcToJst_(results);
					log.info(results);
					// 結果をS3にアップロード
					try {
						putJsonToS3(results);
					} catch(Exception e) {
						e.printStackTrace();
						completionHandler.handle(Future.failedFuture(e.getCause()));
					}
					completionHandler.handle(Future.succeededFuture());
				} else {
					log.error("Communication failed with MongoDB ; " + res.cause());
					log.error("query : " + query);
					completionHandler.handle(Future.failedFuture(res.cause()));
				}
			});
		}
	}

	/**
	 * "DateTime" で終わる属性について、UCT→JSTに変換する.
	 * @param list 変換対象 DEAL オブジェクト リスト
	 */
	private void convertDateTimeFieldUtcToJst_(List<JsonObject> list) {
		for (JsonObject obj : list){
			convertDateTimeFieldUtcToJst_(obj);
		}
	}
	private void convertDateTimeFieldUtcToJst_(JsonObject obj) {
		for (String aKey : obj.fieldNames()) {
			Object aVal = obj.getValue(aKey);
			if (aKey == "$date") {
				String dateTimeUtcString = aVal.toString();
				ZonedDateTime dateTimeUtcZonedDateTime = ZonedDateTime.parse(dateTimeUtcString);
				// OES Project Java の日時まわりの便利ツールを使用するため、日付をフォーマット
				DateTimeFormatter formatter = DateTimeFormatter
					.ofPattern("uuuu/MM/dd-HH:mm:ss");
				String dateTimeUtc = formatter.format(dateTimeUtcZonedDateTime);
				// UTC→JSTの変換のため、＋９時間
				ZonedDateTime zdtUtcToJst = DateTimeUtil
					.toLocalDateTime(dateTimeUtc)
					.plusHours(9)
					.atZone(ZoneId.of("Asia/Tokyo"));
				String iso8601 = DateTimeUtil.toISO8601OffsetString(zdtUtcToJst);
				obj.put(aKey, iso8601);
			} else if (aVal instanceof JsonObject) {
				convertDateTimeFieldUtcToJst_((JsonObject) aVal);
			} else if (aVal instanceof JsonArray) {
				for (Object anObj : (JsonArray) aVal) {
					if (anObj instanceof JsonObject) {
						convertDateTimeFieldUtcToJst_((JsonObject) anObj);
					}
				}
			}
		}
	}

	/**
	 * 取得した融通履歴をS3に格納する.
	 * @param list 変換対象 DEAL オブジェクト リスト
	 */
	private void putJsonToS3(List<JsonObject> list) {
		// S3接続情報
		JsonObject accessInfoS3 = VertxConfig.config.getJsonObject(
			"deallogAcquisition", "accessInfoS3"
		);
		
		// 認証情報
		BasicAWSCredentials credentials = new BasicAWSCredentials(
			accessInfoS3.getString("accessKey"),
			accessInfoS3.getString("secretAccessKey")
		);
		
		// クライアント設定
		ClientConfiguration clientConfig = new ClientConfiguration();
		clientConfig.setProtocol(Protocol.HTTPS);  // プロトコル
		clientConfig.setConnectionTimeout(10000);   // 接続タイムアウト(ms) 
		
		// エンドポイント設定
		EndpointConfiguration endpointConfiguration = new EndpointConfiguration(
			accessInfoS3.getString("endpointUrl"),
			accessInfoS3.getString("regionName")
		);
		
		// AWSのクライアント取得
		AmazonS3 s3 = AmazonS3ClientBuilder.standard()
				.withCredentials(new AWSStaticCredentialsProvider(credentials))
				.withClientConfiguration(clientConfig)
				.withEndpointConfiguration(endpointConfiguration)
				.build();

		// バケット名
		String bucketName = accessInfoS3.getString("bucketName");

		// フォルダ名/ファイル名
		SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMddHHmm");
		String objectPath = accessInfoS3.getString("folderName")
				.concat("/")
				.concat(accessInfoS3.getString("filePrefix"))
				.concat(simpleDateFormat.format(new Date()))
				.concat(accessInfoS3.getString("fileExtension"));

		// Backet存在チェック
		if (!s3.doesBucketExistV2(bucketName)) {
			String message = "Bucket [" + bucketName + "] does not exist ;";
			log.error(message);
			new Throwable(message);
		}

		// 格納
		s3.putObject(bucketName, objectPath, list.toString());

	}}
