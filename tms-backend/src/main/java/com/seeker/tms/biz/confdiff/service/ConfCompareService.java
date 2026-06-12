package com.seeker.tms.biz.confdiff.service;

import com.seeker.tms.biz.confdiff.entities.CommitInfo;
import com.seeker.tms.biz.confdiff.entities.CompareRequestDTO;
import com.seeker.tms.biz.confdiff.entities.PrepareStatusVO;
import com.seeker.tms.biz.confdiff.entities.compare.CompareHistoryVO;
import com.seeker.tms.biz.confdiff.entities.compare.CompareResultVO;

import java.util.List;

public interface ConfCompareService {

    /**
     * 对比同一项目两个 ref(分支+commit)的配置差异
     */
    CompareResultVO compare(CompareRequestDTO request);

    /**
     * 列出项目的远程分支(供前端下拉选择)
     */
    List<String> listBranches(Integer projectId);

    /**
     * 列出某分支上的提交(供前端下拉选择)
     */
    List<CommitInfo> listCommits(Integer projectId, String branch, Integer limit);

    /**
     * 触发项目准备(首次异步 clone),立即返回当前状态
     */
    PrepareStatusVO prepare(Integer projectId);

    /**
     * 查询项目准备状态
     */
    PrepareStatusVO prepareStatus(Integer projectId);

    /**
     * 项目的对比历史摘要列表
     */
    List<CompareHistoryVO> history(Integer projectId);

    /**
     * 按ID获取历史对比结果(回看)
     */
    CompareResultVO getResult(String id);
}
