package com.keji.green.lit.engine.service.impl;

import com.alibaba.fastjson.JSON;
import com.keji.green.lit.engine.common.CommonConverter;
import com.keji.green.lit.engine.dto.bean.InterviewExtraData;
import com.keji.green.lit.engine.dto.request.AskQuestionRequest;
import com.keji.green.lit.engine.dto.request.CreateInterviewRequest;
import com.keji.green.lit.engine.dto.response.*;
import com.keji.green.lit.engine.enums.InterviewStatus;
import com.keji.green.lit.engine.exception.BusinessException;
import com.keji.green.lit.engine.exception.ErrorCode;
import com.keji.green.lit.engine.mapper.InterviewInfoMapper;
import com.keji.green.lit.engine.mapper.InterviewRecordMapper;
import com.keji.green.lit.engine.model.InterviewInfo;
import com.keji.green.lit.engine.model.InterviewRecordWithBLOBs;
import com.keji.green.lit.engine.model.User;
import com.keji.green.lit.engine.service.InterviewService;
import com.keji.green.lit.engine.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static com.keji.green.lit.engine.utils.Constants.FIVE_MINUTE_MILLISECONDS;

/**
 * 面试服务实现类
 *
 * @author xiangjun_lee
 */
@Slf4j
@Service
public class InterviewServiceImpl implements InterviewService {

    @Resource
    private UserService userService;

    @Resource
    private InterviewInfoMapper interviewInfoMapper;

    @Resource
    private InterviewRecordMapper interviewRecordMapper;


    // TODO: 注入算法服务客户端

    /**
     * 创建面试会话
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public InterviewCreateResponse createInterview(CreateInterviewRequest request) {

        // 生成面试ID (UUID)
        String interviewId = UUID.randomUUID().toString();

        InterviewInfo interview = CommonConverter.INSTANCE.convert2InterviewInfo(request);
        interview.setUid(getCurrentUserId());
        interview.setInterviewId(interviewId);
        // 构建面试扩展字段
        InterviewExtraData extraData = CommonConverter.INSTANCE.convert2InterviewExtraData(request);
        interview.setExtraData(JSON.toJSONString(extraData));
        if (interviewInfoMapper.insertSelective(interview) <= 0) {
            throw new BusinessException(ErrorCode.DATABASE_WRITE_ERROR, "创建面试失败");
        }
        return new InterviewCreateResponse(interviewId);
    }

    /**
     * 提问并获取答案（流式）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SseEmitter askQuestion(String interviewId, AskQuestionRequest request) {
        Long uid = getCurrentUserId();
        // 验证面试所有权
        InterviewInfo interviewInfo = interviewInfoMapper.selectByPrimaryKey(interviewId);
        if (Objects.isNull(interviewInfo) || !Objects.equals(uid, interviewInfo.getUid())) {
            throw new BusinessException(ErrorCode.INTERVIEW_NOT_OWNED);
        }
        // 检查面试状态，确保面试处于进行中状态
        if (InterviewStatus.isEnd(interviewInfo.getStatus())) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_ENDED);
        }

        // TODO 检查用户积分是否充足

        // 创建SSE发射器，超时设置为5分钟
        SseEmitter emitter = new SseEmitter(FIVE_MINUTE_MILLISECONDS);

        // 记录面试流水（问题）
        Map<String, Object> queryParam = new HashMap<>();
        queryParam.put("interviewId", interviewId);
        queryParam.put("limit", 5);
        queryParam.put("orderByDesc", "id");
        List<InterviewRecordWithBLOBs> interviewRecordList = interviewRecordMapper.selectQuestionByInterviewId(queryParam);
        List<String> questionList = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(interviewRecordList)) {
            questionList = interviewRecordList.stream().map(InterviewRecordWithBLOBs::getQuestion)
                    .filter(StringUtils::isNotEmpty).toList();
        }
        String currentQuestion = request.getQuestion();
        // TODO: 保存面试流水到数据库
        InterviewRecordWithBLOBs record = new InterviewRecordWithBLOBs();
        record.setInterviewId(interviewId);
        record.setQuestion(request.getQuestion());
        Long recordId = interviewRecordMapper.insertSelective(record);

        if (Objects.isNull(recordId) || recordId <= 0) {
            throw new BusinessException(ErrorCode.DATABASE_WRITE_ERROR, "创建面试流水失败");
        }

        // 更新面试状态为进行中
        if (interviewInfo.getStatus() == InterviewStatus.NOT_STARTED.getCode()) {
            InterviewInfo updateInterview = new InterviewInfo();
            updateInterview.setInterviewId(interviewId);
            updateInterview.setStatus(InterviewStatus.ONGOING.getCode());
            updateInterview.setStartTime(new Date());
            interviewInfoMapper.updateByPrimaryKeySelective(updateInterview);
        }

        // 异步调用算法服务
        CompletableFuture.runAsync(() -> {
            try {
                // TODO: 调用算法服务，获取答案
                // String answer = algorithmClient.getAnswer(request.getQuestion());
                StringBuilder answer = new StringBuilder();

                // 模拟流式返回
                for (int i = 0; i < 10; i++) {
                    String chunk = "这是回答的第" + (i + 1) + "部分。";
                    answer.append(chunk);
                    emitter.send(SseEmitter.event().name("message").data(chunk, org.springframework.http.MediaType.TEXT_PLAIN));
                    Thread.sleep(500);
                }

                // 发送完成事件
                emitter.send(SseEmitter.event().name("complete").data("完成", org.springframework.http.MediaType.TEXT_PLAIN));

                // 更新面试流水（答案）
                LocalDateTime answerTime = LocalDateTime.now();
                // TODO: 更新面试流水的答案
                // interviewRecordMapper.updateAnswerById(recordId, answer.toString(), answerTime);

                emitter.complete();
            } catch (Exception e) {
                log.error("处理面试问题失败: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event().name("error").data("处理问题时发生错误: " + e.getMessage(), org.springframework.http.MediaType.TEXT_PLAIN));
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
            }
        });
        return emitter;
    }

    /**
     * 结束面试
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public InterviewSummaryResponse endInterview(String interviewId) {
        Long uid = getCurrentUserId();
        // 验证面试所有权
        InterviewInfo interviewInfo = interviewInfoMapper.selectByPrimaryKey(interviewId);
        if (Objects.isNull(interviewInfo) || !Objects.equals(uid, interviewInfo.getUid())) {
            throw new BusinessException(ErrorCode.INTERVIEW_NOT_OWNED);
        }
        if (InterviewStatus.isEnd(interviewInfo.getStatus())){
            return CommonConverter.INSTANCE.convert2InterviewSummaryResponse(interviewInfo);
        }

        // 记录结束时间
        InterviewInfo updateInterviewInfo = new InterviewInfo();
        updateInterviewInfo.setInterviewId(interviewId);
        updateInterviewInfo.setStatus(InterviewStatus.ENDED_MANUALLY.getCode());
        updateInterviewInfo.setEndTime(new Date());
        interviewInfoMapper.updateByPrimaryKeySelective(updateInterviewInfo);

        // 构建返回结果
        InterviewSummaryResponse response = new InterviewSummaryResponse();
        response.setInterviewId(interviewId);
        response.setStartTime(interviewInfo.getStartTime());
        response.setEndTime(updateInterviewInfo.getEndTime());
        response.setStatus(updateInterviewInfo.getStatus());
        return response;
    }

    /**
     * 获取面试详情
     */
    @Override
    public InterviewDetailResponse getInterviewDetail(String interviewId) {
        // 验证面试所有权
        Long uid = getCurrentUserId();
        InterviewInfo interviewInfo = interviewInfoMapper.selectByPrimaryKey(interviewId);
        if (Objects.isNull(interviewInfo) || !Objects.equals(uid, interviewInfo.getUid())) {
            throw new BusinessException(ErrorCode.INTERVIEW_NOT_OWNED);
        }
        // 获取面试提问信息
        Map<String, Object> queryParam = new HashMap<>();
        queryParam.put("interviewId", interviewId);
        queryParam.put("orderByDesc", "id");
        List<InterviewRecordWithBLOBs> interviewRecordList = interviewRecordMapper.selectQuestionByInterviewId(queryParam);

        // 构建面试详情响应
        InterviewDetailResponse response = new InterviewDetailResponse();
        response.setInterviewId(interviewId);
        response.setStatus(interviewInfo.getStatus());
        response.setStartTime(interviewInfo.getStartTime());
        if (InterviewStatus.isEnd(interviewInfo.getStatus())){
            response.setEndTime(interviewInfo.getEndTime());
        }
        response.setRecords(CommonConverter.INSTANCE.convert2recordResponseList(interviewRecordList));
        return response;
    }

    /**
     * 分页获取面试列表
     */
    @Override
    public PageResponse<InterviewListResponse> getInterviewList(Integer pageNum, Integer pageSize, InterviewStatus status) {
        // 获取当前用户ID
        Long userId = getCurrentUserId();

        // TODO: 分页查询面试列表
        // Page<Interview> page = new Page<>(pageNum, pageSize);
        // Page<Interview> interviewPage = interviewMapper.selectPageByUserId(page, userId, status);

        // 构建列表响应
        List<InterviewListResponse> responses = new ArrayList<>();

        // 添加模拟数据
        for (int i = 0; i < 3; i++) {
            InterviewListResponse response = new InterviewListResponse();
            response.setInterviewId(UUID.randomUUID().toString());
            response.setStatus(status != null ? status : InterviewStatus.ONGOING);
            response.setStartTime(LocalDateTime.now().minusDays(i));
            if (status == InterviewStatus.ENDED_MANUALLY || status == InterviewStatus.ENDED_AUTOMATICALLY) {
                response.setEndTime(LocalDateTime.now().minusDays(i).plusHours(1));
            }
            response.setTotalPoints(30 + i * 10);
            response.setQuestionCount(3 + i);
            response.setCreateTime(LocalDateTime.now().minusDays(i));
            responses.add(response);
        }

        // return PageResponse.build(responses, interviewPage.getTotal(), pageNum, pageSize);
        return PageResponse.build(responses, responses.size(), pageNum, pageSize);
    }


    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String phone = authentication.getName();
        User user = userService.queryNormalUserByPhone(phone);
        return user.getUid();
    }
} 