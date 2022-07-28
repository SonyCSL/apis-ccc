package jp.co.sony.csl.dcoes.apis.tools.ccc;

import java.text.SimpleDateFormat;
import java.util.Date;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import jp.co.sony.csl.dcoes.apis.common.util.vertx.VertxConfig;
import jp.co.sony.csl.dcoes.apis.tools.ccc.impl.mongo_db.MongoDBDeallogAcquisitionImpl;

/**
 * 融通情報の履歴を取得し、S3に格納する Verticle.
 * {@link jp.co.sony.csl.dcoes.apis.tools.ccc.util.Starter} Verticle から起動される.
 * @author OES Project
 */
public class DeallogAcquisition extends AbstractVerticle {
	private static final Logger log = LoggerFactory.getLogger(DeallogAcquisition.class);

	private Impl impl_;
	private boolean enabled_ = false;
	private long deallogAcquisitionTimer_ = 0L;
	private boolean stopped_ = false;

 	/**
	 * 起動時に呼び出される.
	 * CONFIG から設定を取得し初期化する.
	 * - {@code CONFIG.deallogAcquisition.enabled}
	 * 実装オブジェクトを用意する.
	 * {@link io.vertx.core.eventbus.EventBus} サービスを起動する.
	 * タイマを起動する.
	 * @param startFuture {@inheritDoc}
	 * @throws Exception {@inheritDoc}
	 */

	@Override public void start(Future<Void> startFuture) throws Exception {
		enabled_ = VertxConfig.config.getBoolean(Boolean.TRUE, "deallogAcquisition", "enabled");
		if (enabled_) {
			if (log.isInfoEnabled()) log.info("deallogAcquisition enabled");
			try {
				impl_ = new MongoDBDeallogAcquisitionImpl(vertx);
			} catch (Exception e) {
				startFuture.fail(e);
				return;
			}
		} else {
			if (log.isInfoEnabled()) log.info("deallogAcquisition disabled");
		}

		if (enabled_) deallogAcquisitionTimerHandler_(0L);
		if (log.isTraceEnabled()) log.trace("started : " + deploymentID());
		startFuture.complete();
	}

	/**
	 * Called when stopping.
	 * Sets a flag for stopping the timer.
	 * @throws Exception {@inheritDoc}
	 * 停止時に呼び出される.
	 * タイマを止めるためのフラグを立てる.
	 * @throws Exception {@inheritDoc}
	 */
	@Override public void stop() throws Exception {
		stopped_ = true;
		if (log.isTraceEnabled()) log.trace("stopped : " + deploymentID());
	}

	////

	/**
	 * Sets the timer with the time specified by {@code delay}.
	 * @param delay Time set by timer [ms]
	 * {@code delay} で指定した時間でタイマをセットする.
	 * @param delay タイマ設定時間 [ms]
	 */
	private void setDeallogAcquisitionTimer_(long delay) {
		deallogAcquisitionTimer_ = vertx.setTimer(delay, this::deallogAcquisitionTimerHandler_);
	}
	/**
	 * This process is called by the timer.
	 * @param timerId Timer ID
	 * タイマから呼び出される処理.
	 * @param timerId タイマ ID
	 */
	private void deallogAcquisitionTimerHandler_(Long timerId) {
		if (stopped_) return;
		if (null == timerId || timerId.longValue() != deallogAcquisitionTimer_) {
			if (log.isWarnEnabled()) log.warn("illegal timerId : " + timerId + ", deallogAcquisitionTimer_ : " + deallogAcquisitionTimer_);
			return;
		}

		try {
			// 現在日時（yyyyMMddHHmmssにフォーマット）
			long now = System.currentTimeMillis();
			SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
			Date nowDate = new Date(now);

			// 実行日／実行前日／実行翌日
			String today = simpleDateFormat.format(nowDate).substring(0, 8);
			String yesterday = String.valueOf(Integer.parseInt(today) - 1);

			// 取得開始日時／終了日時
			String acquisitionTimeString = VertxConfig.config.getString("deallogAcquisition", "acquisitionTime");
			long acquisitionTimeStart = simpleDateFormat.parse(yesterday.concat(acquisitionTimeString)).getTime();
			long acquisitionTimeEnd = simpleDateFormat.parse(today.concat(acquisitionTimeString)).getTime();

			// 検索条件
			JsonObject conditions = new JsonObject();
			conditions
					.put("acquisitionTimeStart", acquisitionTimeStart)
					.put("acquisitionTimeEnd", acquisitionTimeEnd);

			impl_.report(conditions, resReport -> {
				if (!resReport.succeeded()) {
					log.error("Communication failed with ServiceCenter ; " + resReport.cause());
					return;
				}
				try {
					// 実行翌日
					String tomorrow = String.valueOf(Integer.parseInt(today) + 1);
					// 次の実行日時
					String executionTimeString = VertxConfig.config.getString("deallogAcquisition", "executionTime");
					long executionTime = simpleDateFormat.parse(tomorrow.concat(executionTimeString)).getTime();
					// 次の実行日時までの間隔
					long delay = executionTime - now;
					log.info(delay);
					setDeallogAcquisitionTimer_(delay);
				} catch (Exception e) {
					log.error(e.getMessage(), e.getCause());
					return;
				}
			});
		} catch (Exception e) {;
			log.error(e.getMessage(), e.getCause());
			return;
		}
	}

	////

	/**
	 * This is the interface for calling the object to be implemented for the reporting process.
	 * @author OES Project
	 * 通知処理の実装オブジェクトを呼び出すためのインタフェイス.
	 * @author OES Project
	 */
	public interface Impl {
		/**
		 * 融通履歴情報を通知する.
		 * @param conditions 検索条件 {@link JsonObject}
		 * @param completionHandler the completion handler
		 */
		void report(JsonObject conditions, Handler<AsyncResult<Void>> completionHandler);
	}

}
