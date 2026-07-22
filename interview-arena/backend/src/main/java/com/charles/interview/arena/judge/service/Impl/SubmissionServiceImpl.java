package com.charles.interview.arena.judge.service.Impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.charles.interview.arena.judge.service.SubmissionService;
import com.charles.interview.arena.mapper.SubmissionMapper;
import com.charles.interview.arena.model.entity.Submission;

/**
 * 代码提交 ServiceImpl
 */
@Service
public class SubmissionServiceImpl extends ServiceImpl<SubmissionMapper, Submission>
        implements SubmissionService {
}
