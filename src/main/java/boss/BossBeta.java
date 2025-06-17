package boss;

import ai.AiConfig;
import ai.AiFilter;
import ai.AiService;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import lombok.SneakyThrows;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static boss.BossElementLocators.*;

import static utils.Bot.sendMessageByTime;
import static utils.JobUtils.formatDuration;

/**
 * @author 小皓
 *         项目链接: <a href=
 *         "https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 *         Boss直聘自动投递Playwright Beta版
 */
public class BossBeta {
    private static final Logger log = LoggerFactory.getLogger(Boss.class);
    static String homeUrl = "https://www.zhipin.com";
    static String baseUrl = "https://www.zhipin.com/web/geek/job?";
    static Set<String> blackCompanies;
    static Set<String> blackRecruiters;
    static Set<String> blackJobs;
    static List<Job> resultList = new ArrayList<>();
    static String dataPath = ProjectRootResolver.rootPath + "/src/main/java/boss/data.json";
    static Date startDate;
    static BossConfig config = BossConfig.init();
    private static Path resumePath;

    public static void main(String[] args) {
        loadData(dataPath);
        PlaywrightUtil.init();
        startDate = new Date();
        login();
        config.getCityCode().forEach(BossBeta::postJobByCityByPlaywright);
        log.info(resultList.isEmpty() ? "未发起新的聊天..." : "新发起聊天公司如下:\n{}",
                resultList.stream().map(Object::toString).collect(Collectors.joining("\n")));
        if (!config.getDebugger()) {
            printResult();
        }
    }

    private static void printResult() {
        String message = String.format("\nBoss投递完成，共发起%d个聊天，用时%s", resultList.size(),
                formatDuration(startDate, new Date()));
        log.info(message);
        sendMessageByTime(message);
        ServerChan.sendMessage("BOSS直聘投递报告",resultList,homeUrl);
        saveData(dataPath);
        resultList.clear();
        if (!config.getDebugger()) {
            PlaywrightUtil.close();
        }
    }

    private static void postJobByCityByPlaywright(String cityCode) {
        String searchUrl = getSearchUrl(cityCode);
        for (String keyword : config.getKeywords()){
            // 使用 URLEncoder 对关键词进行编码
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);

            String url = searchUrl + "&query=" + encodedKeyword;
            log.info("查询岗位链接:{}", url);
            PlaywrightUtil.navigate(url);

            // 记录下拉前后的岗位数量
            int previousJobCount = 0;
            int currentJobCount = 0;
            int unchangedCount = 0;

            if (isJobsPresent()){
                // 获取岗位列表
                log.info("开始获取"+keyword+"岗位信息...");

                Page page = PlaywrightUtil.getPageObject();

                //边滑动边投递
                int currentPosition = 0;
                while (unchangedCount < 2) {
                    // 获取所有岗位卡片
                    List<ElementHandle> jobCards = page.querySelectorAll(JOB_LIST_SELECTOR);
                    currentJobCount = jobCards.size();

                    log.info("当前已加载岗位数量: " + currentJobCount);

                    // 判断是否有新增岗位
                    if (currentJobCount > previousJobCount) {

                        //遍历新加载的岗位
                        for (int i = previousJobCount; i < currentJobCount; i++) {
                            ElementHandle elementHandle = jobCards.get(i);

                            // 排除黑名单岗位
                            String jobName = elementHandle.querySelectorAll(JOB_NAME).get(0).textContent();
                            if (blackJobs.stream().anyMatch(jobName::contains) || !isTargetJob(keyword,jobName)) continue;

                            // 排除黑名单公司
                            String companyName = elementHandle.querySelectorAll(COMPANY_NAME).get(0).textContent();
                            if (blackCompanies.stream().anyMatch(companyName::contains)) continue;

                            //点击岗位查看详情
                            jobCards.get(i).click();
                            PlaywrightUtil.sleep(6);

                            //等待出现HR活跃时间
                            String activeTimeText;
                            try {
                                activeTimeText = PlaywrightUtil.waitForElement(HR_ACTIVE_TIME, 5000).textContent();
                                if (containsDeadStatus(activeTimeText,config.getDeadStatus())) continue;
                            }catch (TimeoutError error){
                                log.info("没有找到【{}】的活跃状态, 默认此岗位将会投递...", jobName+"\t"+companyName);
                            }

                            // 获取招聘人员信息
                            String recruiter = null;
                            try {
                                recruiter = PlaywrightUtil.findElement(RECRUITER_INFO).textContent();
                                if (blackCompanies.stream().anyMatch(recruiter::contains)) continue;
                            }catch (Exception e){
                                log.info("获取招聘人员信息失败:{},默认此岗位将会投递...", e.getMessage());
                            }

                            //获取Job详情页url
                            String href = elementHandle.querySelectorAll(JOB_NAME).get(0).getAttribute("href");

                            //打开详情页
                            try(Page newPage = PlaywrightUtil.getContext().newPage()){
                                newPage.navigate(homeUrl+href);
                                PlaywrightUtil.sleep(3);

                                // 等待聊天按钮出现
                                Locator chatBtn = newPage.locator(CHAT_BUTTON);
                                chatBtn.waitFor(new Locator.WaitForOptions().setTimeout(3000));
                                if (chatBtn.textContent().trim().contains("继续沟通")){
                                    log.warn("该岗位已投递，无需重复投递");
                                    continue;
                                }

                                // 获取薪资
                                String salaryText = null;
                                try {
                                    salaryText = newPage.locator(JOB_DETAIL_SALARY).textContent();
                                    if (isSalaryNotExpected(salaryText)) {
                                        // 过滤薪资
                                        log.info("已过滤:【{}】公司【{}】岗位薪资【{}】不符合投递要求", companyName, jobName, salaryText);
                                        continue;
                                    }
                                }catch (TimeoutError error) {
                                    log.info("获取岗位薪资失败:{}", error.getMessage());
                                }

                                //获取职位描述
                                AiFilter filterResult = null;
                                String jobInfo = null;
                                try {
                                    jobInfo = newPage.locator(JOB_DESCRIPTION).textContent();
                                    // AI检测岗位是否匹配
                                    if (config.getEnableAI()) {
                                        // AI检测岗位是否匹配
                                        filterResult = checkJob(keyword, jobName, jobInfo);
                                    }
                                }catch (Exception e){
                                    log.error("获取岗位详情失败,AI功能对该岗位失效");
                                }

                                if (config.getDebugger()) {
                                    break;
                                }

                                //开始投递
                                chatBtn.click();

                                //检测是否到上限
                                if (isLimit(newPage)) {
                                    log.info("今日投递到达上限，将自动退出程序");
                                    PlaywrightUtil.sleep(1);
                                    System.exit(1);
                                }

                                try{
                                    newPage.locator(DIALOG_TITLE).waitFor(new Locator.WaitForOptions().setTimeout(3000));
                                    Locator closeBtn = newPage.locator(DIALOG_CLOSE);
                                    if (closeBtn.isVisible()){
                                        closeBtn.click();
                                        chatBtn.click();
                                    }
                                } catch (Exception e){
                                    log.error("发起对话失败,默认跳过该岗位");
                                    continue;
                                }

                                try {
                                    Locator chatInput = newPage.locator(CHAT_INPUT);
                                    chatInput.waitFor(new Locator.WaitForOptions().setTimeout(3000));
                                    if (chatInput.isVisible()){
                                        //f12了半天也不知道这个DIALOG_CONTAINER到底指向哪个元素
//                                            Locator dialogElement = newPage.locator(DIALOG_CONTAINER);
//                                            if (dialogElement.isVisible() && dialogElement.textContent().contains("不匹配")){
//                                                newPage.close();
//                                                continue;
//                                            }

                                        chatInput.fill(
                                                filterResult != null && filterResult.getResult()
                                                        && isValidString(filterResult.getMessage())
                                                        ? filterResult.getMessage()
                                                        : config.getSayHi().replaceAll("\\r|\\n", "")
                                        );
                                        PlaywrightUtil.sleep(3);
                                        chatInput.press("Enter");

                                        //发送简历图片
                                        Boolean imgResume = false;
                                        if (config.getSendImgResume()) {
                                            imgResume = sendResumeImage(newPage);
                                        }

                                        String position = jobName + " " + salaryText;
                                        PlaywrightUtil.sleep(2);
                                        log.info("正在投递【{}】公司，【{}】职位，招聘官:【{}】{}", companyName, position, recruiter,
                                                imgResume ? "发送图片简历成功！" : "");

                                        Job job = new Job();
                                        job.setJobName(jobName);
                                        job.setSalary(salaryText);
                                        job.setHref(href);
                                        job.setCompanyName(companyName);
                                        job.setJobInfo(jobInfo);
                                        job.setRecruiter(recruiter);
                                        resultList.add(job);
                                    }
                                } catch (Exception e) {
                                    log.error("发送消息失败:{}", e.getMessage(), e);
                                    newPage.close();
                                }

                            }catch (TimeoutError error) {
                                log.error("无法加载岗位详情页: {},跳过该岗位", error.getMessage());
                            }
                        }

                        previousJobCount = currentJobCount;
                        unchangedCount = 0;
                        currentPosition = (Integer) PlaywrightUtil.evaluate("window.scrollY");

                    } else {
                        unchangedCount++;
                        log.info("岗位数量未增加,执行向下滑动");

                        Integer randomNum = (int) (Math.random() * (8 - 4 + 1)) + 4;
                        //获取滑动高度
                        Integer scrollHeight = (Integer) PlaywrightUtil.evaluate("document.documentElement.scrollHeight");
                        Integer clientHeight = (Integer) PlaywrightUtil.evaluate("document.documentElement.clientHeight");

                        // 滚动到页面底部加载更多
                        while (currentPosition >= scrollHeight-clientHeight){
                            int i = 10*randomNum+500;
                            currentPosition += i;
                            page.mouse().wheel(0,i);
                            page.waitForTimeout(100*randomNum);
                        }
                        log.info("下拉页面加载更多...");

                        // 等待新内容加载
                        page.waitForTimeout(500*randomNum);
                    }
                }
                log.info("已获取所有{}可加载岗位，共计: {} 个",keyword,currentJobCount);
            }
        }
    }

    /**
     * 发送简历图片
     * @param page
     * @return 是否发送成功
     */
    private static Boolean sendResumeImage(Page page) {
        try {
            Locator fileInput = page.locator(IMAGE_UPLOAD);
            fileInput.waitFor(new Locator.WaitForOptions().setTimeout(3000));
            fileInput.setInputFiles(getResumeImage());
            return true;
        }catch (Exception e){
            log.error("发送简历图片时出错：{}", e.getMessage());
            return false;
        }
    }

    private static void loadData(String path) {
        try {
            String json = new String(Files.readAllBytes(Paths.get(path)));
            parseJson(json);
        } catch (IOException e) {
            log.error("读取【{}】数据失败！", path);
        }
    }

    private static void parseJson(String json) {
        JSONObject jsonObject = new JSONObject(json);
        blackCompanies = jsonObject.getJSONArray("blackCompanies").toList().stream().map(Object::toString)
                .collect(Collectors.toSet());
        blackRecruiters = jsonObject.getJSONArray("blackRecruiters").toList().stream().map(Object::toString)
                .collect(Collectors.toSet());
        blackJobs = jsonObject.getJSONArray("blackJobs").toList().stream().map(Object::toString)
                .collect(Collectors.toSet());
    }

    private static void login() {
        //打开网站
        log.info("打开Boss直聘网站中...");
        PlaywrightUtil.navigate(homeUrl);
        if (isLoginRequired()){
            log.error("cookie失效，尝试扫码登录...");
            scanLogin();
        }
    }

    private static boolean isLoginRequired() {
        try{
            Locator locator = PlaywrightUtil.waitForElement(LOGIN_BTNS,6000);
            String text = locator.textContent();
            return (text !=null && text.trim().contains("登录")) ? true : false;
        }catch (TimeoutError error){
            return false;
        }
    }

    @SneakyThrows
    private static void scanLogin(){
        // 访问登录页面
        PlaywrightUtil.navigate(homeUrl + "/web/user/?ka=header-login");
        PlaywrightUtil.sleep(3);

        // 1. 如果已经登录，则直接返回
        try{
            Locator locator = PlaywrightUtil.waitForElement(LOGIN_BTNS,3000);
            String text = locator.textContent();
            if (text !=null && text.trim().contains("登录")){
                log.info("已经登录，直接开始投递...");
                return;
            }
        }catch (TimeoutError error){
            log.info("等待登录...");
        }

        // 2. 定位二维码登录的切换按钮
        Locator element = PlaywrightUtil.findElement(LOGIN_SCAN_SWITCH);
        if (!element.isVisible()){
            element = PlaywrightUtil.findElement(LOGIN_CODE_SWITCH);
            if (!element.isVisible()){
                log.error("未找到二维码登录按钮");
                throw new RuntimeException("找不到登录页面元素");
            }else {
                log.warn("已在二维码登录页面");
            }
        }else {
            // 尝试点击二维码按钮并等待页面出现已登录的元素
            element.click();
        }


        // 3. 登录逻辑
        boolean login = false;

        // 4. 记录开始时间，用于判断10分钟超时
        final long TIMEOUT = System.currentTimeMillis()+10 * 60 * 1000; // 10分钟

        // 5. 用于监听用户是否在控制台回车
        Scanner scanner = new Scanner(System.in);

        while (!login){
            // 如果已经超过10分钟，退出程序
            long elapsed = System.currentTimeMillis();
            if (elapsed >= TIMEOUT){
                log.error("超过10分钟未完成登录，程序退出...");
                System.exit(1);
            }
            try {
                PlaywrightUtil.waitForElement(LOGIN_SUCCESS_HEADER, 2000);
                PlaywrightUtil.waitForElement(LOGIN_SUCCESS_INDICATOR, 2000);
                // 如果上述元素都能找到，说明登录成功
                login = true;
                log.info("登录成功！");
                //因为改用--user-data-dir参数启动浏览器自动保存cookie
            }catch(TimeoutError e){
                // 登录失败
                log.error("登录失败，等待用户操作或者 2 秒后重试...");

                // 每次登录失败后，等待2秒，同时检查用户是否按了回车
                boolean userInput = waitForUserInputOrTimeout(scanner);
                if (userInput) {
                    log.info("检测到用户输入，继续尝试登录...");
                }
            }
        }
    }

    /**
     * 在指定的毫秒数内等待用户输入回车；若在等待时间内用户按回车则返回 true，否则返回 false。
     *
     * @param scanner 用于读取控制台输入
     * @return 用户是否在指定时间内按回车
     */
    private static boolean waitForUserInputOrTimeout(Scanner scanner) {
        long end = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < end) {
            try {
                // 判断输入流中是否有可用字节
                if (System.in.available() > 0) {
                    // 读取一行（用户输入）
                    scanner.nextLine();
                    return true;
                }
            } catch (IOException e) {
                // 读取输入流异常，直接忽略
            }

            // 小睡一下，避免 CPU 空转
            SeleniumUtil.sleep(1);
        }
        return false;
    }

    /**
     * 构建查询url
     * @param cityCode
     * @return
     */
    private static String getSearchUrl(String cityCode) {
        return baseUrl + JobUtils.appendParam("city", cityCode) +
                JobUtils.appendParam("jobType", config.getJobType()) +
                JobUtils.appendParam("salary", config.getSalary()) +
                JobUtils.appendListParam("experience", config.getExperience()) +
                JobUtils.appendListParam("degree", config.getDegree()) +
                JobUtils.appendListParam("scale", config.getScale()) +
                JobUtils.appendListParam("industry", config.getIndustry()) +
                JobUtils.appendListParam("stage", config.getStage());
    }

    private static boolean isJobsPresent() {
        try {
            // 判断页面是否存在岗位的元素
            PlaywrightUtil.waitForElement(JOB_LIST_CONTAINER);
            return true;
        } catch (TimeoutError e) {
            log.error("加载岗位区块失败:{}", e.getMessage());
            return false;
        }
    }

    /**
     * 与AI相关工作筛选
     * @param keyword
     * @param jobName
     * @return
     */
    private static boolean isTargetJob(String keyword, String jobName) {
        boolean keywordIsAI = false;
        for (String target : new String[]{"大模型", "AI"}) {
            if (keyword.contains(target)) {
                keywordIsAI = true;
                break;
            }
        }

        boolean jobIsDesign = false;
        for (String designOrVision : new String[]{"设计", "视觉", "产品", "运营"}) {
            if (jobName.contains(designOrVision)) {
                jobIsDesign = true;
                break;
            }
        }

        boolean jobIsAI = false;
        for (String target : new String[]{"AI", "人工智能", "大模型", "生成"}) {
            if (jobName.contains(target)) {
                jobIsAI = true;
                break;
            }
        }

        if (keywordIsAI) {
            if (jobIsDesign) {
                return false;
            } else if (!jobIsAI) {
                return true;
            }
        }
        return true;
    }

    /**
     * 过滤掉HR状态
     * @param activeTimeText
     * @param deadStatus
     * @return
     */
    public static boolean containsDeadStatus(String activeTimeText, List<String> deadStatus) {
        for (String status : deadStatus) {
            if (activeTimeText.contains(status)) {
                return true;// 一旦找到包含的值，立即返回 true
            }
        }
        return false;// 如果没有找到，返回 false
    }

    /**
     * 检查岗位薪资是否符合预期
     *
     * @return boolean
     *         true 不符合预期
     *         false 符合预期
     *         期望的最低薪资如果比岗位最高薪资还小，则不符合（薪资给的太少）
     *         期望的最高薪资如果比岗位最低薪资还小，则不符合(要求太高满足不了)
     */
    private static boolean isSalaryNotExpected(String salary) {
        try {
            // 1. 如果没有期望薪资范围，直接返回 false，表示"薪资并非不符合预期"
            List<Integer> expectedSalary = config.getExpectedSalary();
            if (!hasExpectedSalary(expectedSalary)) {
                return false;
            }

            // 2. 清理薪资文本（比如去掉 "·15薪"）
            salary = removeYearBonusText(salary);

            // 3. 如果薪资格式不符合预期（如缺少 "K" / "k"），直接返回 true，表示"薪资不符合预期"
            if (!isSalaryInExpectedFormat(salary)) {
                return true;
            }

            // 4. 进一步清理薪资文本，比如去除 "K"、"k"、"·" 等
            salary = cleanSalaryText(salary);

            // 5. 判断是 "月薪" 还是 "日薪"
            String jobType = detectJobType(salary);
            salary = removeDayUnitIfNeeded(salary); // 如果是按天，则去除 "元/天"

            // 6. 解析薪资范围并检查是否超出预期
            Integer[] jobSalaryRange = parseSalaryRange(salary);
            return isSalaryOutOfRange(jobSalaryRange,
                    getMinimumSalary(expectedSalary),
                    getMaximumSalary(expectedSalary),
                    jobType);

        } catch (Exception e) {
            log.error("岗位薪资获取异常！薪资文本【{}】,异常信息【{}】", salary, e.getMessage(), e);
            // 出错时，您可根据业务需求决定返回 true 或 false
            // 这里假设出错时无法判断，视为不满足预期 => 返回 true
            return true;
        }
    }

    /**
     * 是否存在有效的期望薪资范围
     */
    private static boolean hasExpectedSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && !expectedSalary.isEmpty();
    }

    /**
     * 去掉年终奖信息，如 "·15薪"、"·13薪"。
     */
    private static String removeYearBonusText(String salary) {
        if (salary.contains("薪")) {
            // 使用正则去除 "·任意数字薪"
            return salary.replaceAll("·\\d+薪", "");
        }
        return salary;
    }

    private static boolean isSalaryInExpectedFormat(String salaryText) {
        return salaryText.contains("K") || salaryText.contains("k") || salaryText.contains("元/天");
    }

    private static String cleanSalaryText(String salaryText) {
        salaryText = salaryText.replace("K", "").replace("k", "");
        int dotIndex = salaryText.indexOf('·');
        if (dotIndex != -1) {
            salaryText = salaryText.substring(0, dotIndex);
        }
        return salaryText;
    }

    /**
     * 判断是否是按天计薪，如发现 "元/天" 则认为是日薪
     */
    private static String detectJobType(String salary) {
        if (salary.contains("元/天")) {
            return "day";
        }
        return "mouth";
    }

    /**
     * 如果是日薪，则去除 "元/天"
     */
    private static String removeDayUnitIfNeeded(String salary) {
        if (salary.contains("元/天")) {
            return salary.replaceAll("元/天", "");
        }
        return salary;
    }

    private static Integer[] parseSalaryRange(String salaryText) {
        try {
            return Arrays.stream(salaryText.split("-")).map(s -> s.replaceAll("[^0-9]", "")) // 去除非数字字符
                    .map(Integer::parseInt) // 转换为Integer
                    .toArray(Integer[]::new); // 转换为Integer数组
        } catch (Exception e) {
            log.error("薪资解析异常！{}", e.getMessage(), e);
        }
        return null;
    }

    private static boolean isSalaryOutOfRange(Integer[] jobSalary, Integer miniSalary, Integer maxSalary,
                                              String jobType) {
        if (jobSalary == null) {
            return true;
        }
        if (miniSalary == null) {
            return false;
        }
        if (Objects.equals("day", jobType)) {
            // 期望薪资转为平均每日的工资
            maxSalary = BigDecimal.valueOf(maxSalary).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(21.75), 0, RoundingMode.HALF_UP).intValue();
            miniSalary = BigDecimal.valueOf(miniSalary).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(21.75), 0, RoundingMode.HALF_UP).intValue();
        }
        // 如果职位薪资下限低于期望的最低薪资，返回不符合
        if (jobSalary[1] < miniSalary) {
            return true;
        }
        // 如果职位薪资上限高于期望的最高薪资，返回不符合
        return maxSalary != null && jobSalary[0] > maxSalary;
    }

    private static Integer getMinimumSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && !expectedSalary.isEmpty() ? expectedSalary.get(0) : null;
    }

    private static Integer getMaximumSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && expectedSalary.size() > 1 ? expectedSalary.get(1) : null;
    }

    /**
     * 请求AI判断职位
     * @param keyword
     * @param jobName
     * @param jd
     * @return
     */
    private static AiFilter checkJob(String keyword, String jobName, String jd) {
        AiConfig aiConfig = AiConfig.init();
        String requestMessage = String.format(aiConfig.getPrompt(), aiConfig.getIntroduce(), keyword, jobName, jd,
                config.getSayHi());
        String result = AiService.sendRequest(requestMessage);
        return result.contains("false") ? new AiFilter(false) : new AiFilter(true, result);
    }

    private static boolean isLimit(Page page) {
        try {
            PlaywrightUtil.sleep(1);
            String text = page.locator(DIALOG_CON).textContent();
            return text.contains("已达上限");
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isValidString(String str) {
        return str != null && !str.isEmpty();
    }

    private static void saveData(String path) {
        try {
            updateListData();
            Map<String, Set<String>> data = new HashMap<>();
            data.put("blackCompanies", blackCompanies);
            data.put("blackRecruiters", blackRecruiters);
            data.put("blackJobs", blackJobs);
            String json = customJsonFormat(data);
            Files.write(Paths.get(path), json.getBytes());
        } catch (IOException e) {
            log.error("保存【{}】数据失败！", path);
        }
    }

    private static void updateListData() {
        PlaywrightUtil.navigate("https://www.zhipin.com/web/geek/chat");
        PlaywrightUtil.sleep(3);

        Page page = PlaywrightUtil.getPageObject();

        int size = resultList.size();
        int read = 0;
        //上一轮40个最后的公司名
        String lastCompanyName = null;

        while (read<=size){
            List<ElementHandle> items = page.querySelectorAll(CHAT_LIST_ITEM);
            //查找起始位
            int start = 0;
            if(lastCompanyName != null){
                start = selectLastPosition(items,lastCompanyName);
                if (start == -1){
                    log.warn("未找到上次结尾的公司名【{}】,将退出添加黑名单公司任务", lastCompanyName);
                    break; // 无法衔接，终止循环
                }
            }

            String currentLastCompany = null;
            for (int i = start; i < items.size(); i++) {
                ElementHandle item = items.get(i);
                try {
                    currentLastCompany = item.querySelector(COMPANY_NAME_IN_CHAT).textContent();
                    String message = item.querySelector(LAST_MESSAGE).textContent();
                    if (!currentLastCompany.isBlank() && !message.isBlank()){
                        boolean match = message.contains("不") || message.contains("感谢") || message.contains("但")
                                || message.contains("遗憾") || message.contains("需要本") || message.contains("对不");
                        boolean nomatch = message.contains("不是") || message.contains("不生");
                        if (match && !nomatch) {
                            log.info("黑名单公司：【{}】，信息：【{}】", currentLastCompany, message);
                            if (blackCompanies.stream().anyMatch(currentLastCompany::contains)) {
                                continue;
                            }
//                            currentLastCompany = currentLastCompany.replaceAll("\\.{3}", "");
                            if (currentLastCompany.matches(".*(\\p{IsHan}{2,}|[a-zA-Z]{4,}).*")) {
                                blackCompanies.add(currentLastCompany);
                            }
                        }
                    }
                }catch (Exception e){
                    log.warn("获取第{}个对话信息失败，默认跳过加入黑名单");
                }
                if (++read>size){
                    return;
                }
            }
            lastCompanyName = currentLastCompany;

            //滑动到底部
            PlaywrightUtil.evaluate("var container = document.querySelector('.user-list-content');"+
                "container.scrollTo(0, container.scrollHeight);");
            PlaywrightUtil.sleep(2);
        }
        log.info("黑名单公司数量：{}", blackCompanies.size());
    }

    private static int selectLastPosition(List<ElementHandle> items,String lastCompanyName){
        for (int i = 0; i < items.size(); i++) {
            try {
//                String CompanyName = items.get(i).querySelector(COMPANY_NAME_IN_CHAT).textContent();
                if (lastCompanyName.equals(items.get(i).querySelector(COMPANY_NAME_IN_CHAT).textContent())){
                    return i+1;
                }
            }catch (Exception e){
                log.warn("比较公司名时发生异常", e);
            }
        }
        return -1; //未找到
    }

    private static String customJsonFormat(Map<String, Set<String>> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        for (Map.Entry<String, Set<String>> entry : data.entrySet()) {
            sb.append("    \"").append(entry.getKey()).append("\": [\n");
            sb.append(entry.getValue().stream().map(s -> "        \"" + s + "\"").collect(Collectors.joining(",\n")));

            sb.append("\n    ],\n");
        }
        sb.delete(sb.length() - 2, sb.length());
        sb.append("\n}");
        return sb.toString();
    }

    /**
     * 获取简历文件
     * @return 简历文件
     */
    private static Path getResumeImage(){
        if (resumePath == null){
            // 从类路径加载 resume.jpg
            URL resourceUrl = BossBeta.class.getResource("/resume.jpg");
            if (resourceUrl != null) {
                try {
                    resumePath = Path.of(resourceUrl.toURI());
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
            }else {
                log.error("未检测到简历图片,请检查resource文件夹");
//                config.setSendImgResume(false);
            }
        }
        return resumePath;
    }

    /**
     * 统一异常处理框架
     * @param action
     * @param errorMessage
     */
    private static void safeExecute(Runnable action, String errorMessage) {
        try {
            action.run();
        } catch (TimeoutError e) {
            log.warn("{}: {}", errorMessage, e.getMessage());
        } catch (Exception e) {
            log.error("{}: {}", errorMessage, e.getMessage(), e);
        }
    }
}
