package io.metersphere.websocket;

import com.alibaba.fastjson.JSON;
import io.metersphere.api.jmeter.MessageCache;
import io.metersphere.api.jmeter.TestResult;
import io.metersphere.api.service.MsResultService;
import io.metersphere.commons.utils.LogUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ServerEndpoint("/api/scenario/report/get/real/{reportId}")
@Component
public class ScenarioReportWebSocket {

    private static MsResultService resultService;
    private static ConcurrentHashMap<Session, Timer> refreshTasks = new ConcurrentHashMap<>();

    @Resource
    public void setReportService(MsResultService resultService) {
        ScenarioReportWebSocket.resultService = resultService;
    }

    /**
     * 开启连接的操作
     */
    @OnOpen
    public void onOpen(@PathParam("reportId") String reportId, Session session) {
        MessageCache.reportCache.put(reportId, session);
        Timer timer = new Timer(true);
        ApiDebugResultTask task = new ApiDebugResultTask(session, reportId);
        timer.schedule(task, 0, 1000);
        refreshTasks.putIfAbsent(session, timer);
    }

    /**
     * 连接关闭的操作
     */
    @OnClose
    public void onClose(Session session) {
        Timer timer = refreshTasks.get(session);
        if (timer != null) {
            timer.cancel();
            refreshTasks.remove(session);
        }
        // 清理掉过程数据
        List<String> reports = MessageCache.reportCache.entrySet().stream()
                .filter(x -> x.getValue() == session).map(x -> x.getKey())
                .collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(reports)) {
            resultService.delete(reports.get(0));
        }
    }

    /**
     * 给服务器发送消息告知数据库发生变化
     */
    @OnMessage
    public void onMessage(@PathParam("reportId") String reportId, Session session, String message) {
        try {
            Timer timer = refreshTasks.get(session);
            if (timer != null) {
                timer.cancel();
            }
            Timer newTimer = new Timer(true);
            newTimer.schedule(new ApiDebugResultTask(session, reportId), 0, 1000L);
            refreshTasks.put(session, newTimer);
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
        }
    }

    /**
     * 出错的操作
     */
    @OnError
    public void onError(Throwable error) {
        System.out.println(error);
        error.printStackTrace();
    }

    public static class ApiDebugResultTask extends TimerTask {
        private Session session;
        private String reportId;

        ApiDebugResultTask(Session session, String reportId) {
            this.session = session;
            this.reportId = reportId;
        }

        @Override
        public void run() {
            try {
                if (!session.isOpen()) {
                    return;
                }
                TestResult report = resultService.synSampleResult(reportId);
                if (report != null) {
                    session.getBasicRemote().sendText(JSON.toJSONString(report));
                    if (report.isEnd()) {
                        session.close();
                    }
                }
            } catch (Exception e) {
                LogUtil.error(e.getMessage(), e);
            }
        }
    }
}