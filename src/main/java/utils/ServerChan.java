package utils;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.core5.http.ContentType;
import org.json.JSONObject;

import java.net.URL;
import java.util.HashMap;
import java.util.List;

/**
 * @author 小皓
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 */

@Slf4j
public class ServerChan {
    private static final String SEND_KEY;

    static {
        HashMap<String,String> browserConfig = JobUtils.getConfig(HashMap.class, "ServerChan");
        SEND_KEY = browserConfig.get("key");
    }

    /**
     * 推送消息
     * @param title
     * @param jobList
     * @param baseUrl
     */
    public static void sendMessage(String title,List<Job> jobList,String baseUrl){
        if (SEND_KEY==null || SEND_KEY.isBlank()) return;

        // 构建 JSON 请求体
        JSONObject requestData = new JSONObject();
        requestData.put("title", title);
        requestData.put("desp", makeMarkdown(jobList,baseUrl));
        // 发送HTTP请求
        try{
            String response = Request.post(String.format("https://sctapi.ftqq.com/%s.send",SEND_KEY))
                    .bodyString(requestData.toString(),
                            ContentType.APPLICATION_JSON)
                    .execute()
                    .returnContent()
                    .asString();
            log.info("消息推送成功: {}", response);
        }catch (Exception e) {
            e.printStackTrace();
            log.error("消息推送失败: {}", e.getMessage());
        }
    }

    /**
     * 生成markdwon
     * @param jobList
     * @return
     */
    private static String makeMarkdown(List<Job> jobList,String baseUrl){
        StringBuilder stringBuilder = new StringBuilder();
        String msgItem;
        for (Job job : jobList){
            msgItem = String.format(
                    "# %s\n\n#### %s\t%s\n\n##### %s\n\n[岗位链接](%s)\n\n\n\n",
                    job.getJobName(),
                    job.getSalary(),
                    job.getJobArea(),
                    job.getCompanyName(),
                    baseUrl+job.getHref()
            );
            stringBuilder.append(msgItem);
        }
        return stringBuilder.toString();
    }
}
