package jp.co.sony.csl.dcoes.apis.tools.ccc.impl.http_post;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jp.co.sony.csl.dcoes.apis.common.util.StringUtil;
import jp.co.sony.csl.dcoes.apis.common.util.vertx.VertxConfig;
import jp.co.sony.csl.dcoes.apis.tools.ccc.ScenarioAcquisition;

/**
 * Implements acquisition of SCENARIO via HTTP POST for web service.
 * Used in {@link ScenarioAcquisition}.
 * @author OES Project
 * ウェブサービスに対して HTTP POST で SCENARIO を取得する実装.
 * {@link ScenarioAcquisition} で使用される.
 * @author OES Project
 */
public class HttpPostScenarioAcquisitionImpl implements ScenarioAcquisition.Impl {
	private static final Logger log = LoggerFactory.getLogger(HttpPostScenarioAcquisitionImpl.class);

	/**
	 * This is the default HTTP connection timeout value [ms].
	 * The value is {@value}.
	 * HTTP 接続のタイムアウトのデフォルト値 [ms].
	 * 値は {@value}.
	 */
	private static final Long DEFAULT_REQUEST_TIMEOUT_MSEC = 5000L;

	private Vertx vertx_;
	private HttpClient client_;
	private String uri_;

	/**
	 * Creates instance.
	 * Gets settings from CONFIG and initializes. 
	 * - CONFIG.scenarioAcquisition.host : Connection destination host name [{@link String}]
	 * - CONFIG.scenarioAcquisition.ssl : SSL flag [{@link Boolean}]
	 * - CONFIG.scenarioAcquisition.sslTrustAll : OK flag [{@link Boolean}] for any SSL
	 * - CONFIG.scenarioAcquisition.port : Connection destination port [{@link Integer}].
	 *                                     If there are no settings, 443 for SSL, 80 for all else.
	 * - CONFIG.scenarioAcquisition.uri : Connection URI [{@link String}]
	 * @param vertx vertx object
	 * インスタンスを作成する.
	 * CONFIG から設定を取得し初期化する.
	 * - CONFIG.scenarioAcquisition.host : 接続先ホスト名 [{@link String}]
	 * - CONFIG.scenarioAcquisition.ssl : SSL フラグ [{@link Boolean}]
	 * - CONFIG.scenarioAcquisition.sslTrustAll : SSL なんでも OK フラグ [{@link Boolean}]
	 * - CONFIG.scenarioAcquisition.port : 接続先ポート [{@link Integer}].
	 *                                     設定がない場合 SSL なら 443, そうでなければ 80.
	 * - CONFIG.scenarioAcquisition.uri : 接続先 URI [{@link String}]
	 * @param vertx vertx オブジェクト
	 */
	public HttpPostScenarioAcquisitionImpl(Vertx vertx) {
		vertx_ = vertx;
		String host = VertxConfig.config.getString("scenarioAcquisition", "host");
		Boolean isSsl = VertxConfig.config.getBoolean(false, "scenarioAcquisition", "ssl");
		Integer port = (isSsl) ? VertxConfig.config.getInteger(443, "scenarioAcquisition", "port") : VertxConfig.config.getInteger(80, "scenarioAcquisition", "port");
		Boolean sslTrustAll = VertxConfig.config.getBoolean(false, "scenarioAcquisition", "sslTrustAll");
		uri_ = VertxConfig.config.getString("scenarioAcquisition", "uri");
		if (log.isInfoEnabled()) log.info("host : " + host);
		if (log.isInfoEnabled()) log.info("port : " + port);
		if (isSsl) if (log.isInfoEnabled()) log.info("sslTrustAll : " + sslTrustAll);
		if (log.isInfoEnabled()) log.info("uri : " + uri_);
		client_ = vertx_.createHttpClient(new HttpClientOptions().setDefaultHost(host).setDefaultPort(port).setSsl(isSsl).setTrustAll(sslTrustAll));
	}

	/**
	 * {@inheritDoc}
	 */
	@Override public void acquireCurrent(String account, String password, String unitId, Handler<AsyncResult<JsonObject>> completionHandler) {
		Buffer body = Buffer.buffer();
		body.appendString("account=").appendString(StringUtil.urlEncode(account));
		body.appendString("&password=").appendString(StringUtil.urlEncode(password));
		body.appendString("&communityId=").appendString(StringUtil.urlEncode(VertxConfig.communityId()));
		body.appendString("&clusterId=").appendString(StringUtil.urlEncode(VertxConfig.clusterId()));
		body.appendString("&unitId=").appendString(StringUtil.urlEncode(unitId));
		body.appendString("&isMD5Password=true");
		if (log.isDebugEnabled()) log.debug("body : " + body);
		new Poster_(body).execute_(completionHandler);
	}

	////

	private class Poster_ {
		private Buffer body_;
		private boolean completed_ = false;
		private Poster_(Buffer body) {
			body_ = body;
		}
		/**
		 * Executes HTTP POST processing.
		 * (Maybe because of poor implementation) The result may be returned twice, so this is blocked here.
		 * @param completionHandler The completion handler
		 * HTTP POST 処理実行.
		 * ( 実装がまずいのか ) 二度結果が返ってくることがあるためここでブロックする.
		 * @param completionHandler the completion handler
		 */
		private void execute_(Handler<AsyncResult<JsonObject>> completionHandler) {
			post_(r -> {
				if (!completed_) {
					completed_ = true;
					completionHandler.handle(r);
				} else {
					if (log.isWarnEnabled()) log.warn("post_() result returned more than once : " + r);
				}
			});
		}
		private void post_(Handler<AsyncResult<JsonObject>> completionHandler) {
			Long requestTimeoutMsec = VertxConfig.config.getLong(DEFAULT_REQUEST_TIMEOUT_MSEC, "scenarioAcquisition", "requestTimeoutMsec");
			client_.post(uri_, resPost -> {
				if (log.isDebugEnabled()) log.debug("status : " + resPost.statusCode());
				if (resPost.statusCode() == 200) {
					resPost.bodyHandler(buffer -> {
						String resp = String.valueOf(buffer);
						if (0 < resp.length()) {
							JsonObject result = new JsonObject(resp);
							if (log.isDebugEnabled()) log.debug("result : " + result);
							completionHandler.handle(Future.succeededFuture(result));
						} else {
							if (log.isDebugEnabled()) log.debug("result : null");
							completionHandler.handle(Future.succeededFuture());
						}
					}).exceptionHandler(t -> {
						completionHandler.handle(Future.failedFuture(t));
					});
				} else {
					resPost.bodyHandler(error -> {
						completionHandler.handle(Future.failedFuture("http post failed : " + resPost.statusCode() + " : " + resPost.statusMessage() + " : " + error));
					}).exceptionHandler(t -> {
						completionHandler.handle(Future.failedFuture("http post failed : " + resPost.statusCode() + " : " + resPost.statusMessage() + " : " + t));
					});
				}
			}).setTimeout(requestTimeoutMsec).exceptionHandler(t -> {
				completionHandler.handle(Future.failedFuture(t));
			}).putHeader("content-type", "application/x-www-form-urlencoded").putHeader("content-length", String.valueOf(body_.length())).write(body_).end();
		}
	}

	@Override public void acquireCurrentS3(String unitId, Handler<AsyncResult<JsonObject>> completionHandler) {
		log.info("unitId : ".concat(unitId));

		// S3接続情報
		JsonObject accessInfoS3 = VertxConfig.config.getJsonObject(
			"scenarioAcquisition", "accessInfoS3"
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

		// フォルダ名/ファイル名（接頭辞）
		String objectPathPrefix = accessInfoS3.getString("folderName")
				.concat("/")
				.concat(accessInfoS3.getString("filePrefix")
				.concat(unitId)
				.concat("_"));

		// Backet存在チェック
		if (!s3.doesBucketExistV2(bucketName)) {
			String message = "Bucket [" + bucketName + "] does not exist ;";
			log.error(message);
			completionHandler.handle(Future.failedFuture(new Throwable(message)));
		}

		// S3オブジェクトリスト取得用リクエストを作成
		ListObjectsV2Request listObjectsRequest = new ListObjectsV2Request()
			.withBucketName(bucketName)
			.withPrefix(objectPathPrefix)
			.withDelimiter("/");

		// S3オブジェクトリスト取得
		ListObjectsV2Result result = s3.listObjectsV2(listObjectsRequest);
		
		// S3オブジェクトリストから最新のオブジェクトのキーを取得
		String objectKey = "";
		SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd");
		String today = simpleDateFormat.format(new Date());

		List<S3ObjectSummary> s3ObjectSummaries = result.getObjectSummaries();
		for(S3ObjectSummary s3ObjectSummary : s3ObjectSummaries){
			// 取得したS3オブジェクトリストからファイル名が最大となったオブジェクトを対象とする。
			String objectKeyCurrentObject = s3ObjectSummary.getKey();
			if (objectKey.isEmpty()
				|| objectKey.compareTo(objectKeyCurrentObject) < 0) {
				// 実行日の日付と一致する日付のオブジェクトのみ対象とする。
				String objectKeyCurrentObjectDateTime = objectKeyCurrentObject
					.replace(objectPathPrefix, "")
					.replace(accessInfoS3.getString("fileExtension"), "");
				String objectKeyCurrentObjectDate = objectKeyCurrentObjectDateTime.substring(0, 8);
				if (today.equals(objectKeyCurrentObjectDate)) {
					objectKey = objectKeyCurrentObject;
				}
			}
		}
		log.info("objectKey : ".concat(objectKey));

		if (objectKey.isEmpty()) {
			String message = "Object does not exist ;";
			log.error(message);
			completionHandler.handle(Future.failedFuture(new Throwable(message)));
		} else {
			// S3オブジェクト取得用リクエストを作成
			GetObjectRequest objectRequest = new GetObjectRequest(
					bucketName, objectKey);

			// S3Object(Json)を取得
			S3Object s3Object = s3.getObject(objectRequest);

			// S3Object→JsonObjectに変換
			S3ObjectInputStream s3Stream = s3Object.getObjectContent();
			ObjectMapper objectMapper = new ObjectMapper();
			Map<String, Object> inputJsonMap;
			try {
				inputJsonMap = objectMapper.readValue(s3Stream, new TypeReference<Map<String, Object>>() {
				});
				JsonObject object = new JsonObject(inputJsonMap);
				completionHandler.handle(Future.succeededFuture(object));
			} catch (JsonParseException e) {
				e.printStackTrace();
				completionHandler.handle(Future.failedFuture(new Throwable(e.getMessage(), e.getCause())));
			} catch (JsonMappingException e) {
				e.printStackTrace();
				completionHandler.handle(Future.failedFuture(new Throwable(e.getMessage(), e.getCause())));
			} catch (IOException e) {
				e.printStackTrace();
				completionHandler.handle(Future.failedFuture(new Throwable(e.getMessage(), e.getCause())));
			}
		}
	}
}
