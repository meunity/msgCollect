package com.msgc.service.impl;

import com.msgc.constant.FilePath;
import com.msgc.constant.SessionKey;
import com.msgc.constant.enums.TableStatusEnum;
import com.msgc.constant.response.ResponseWrapper;
import com.msgc.entity.*;
import com.msgc.entity.bo.AnswerRecordBO;
import com.msgc.entity.dto.CommentDTO;
import com.msgc.entity.dto.TableDTO;
import com.msgc.repository.ICommentRepository;
import com.msgc.repository.ITableRepository;
import com.msgc.repository.IUnfilledRecordRepository;
import com.msgc.service.*;
import com.msgc.utils.FileTransportUtil;
import com.msgc.utils.FileUtil;
import com.msgc.utils.JsonUtil;
import com.msgc.utils.WebUtil;
import com.msgc.utils.excel.ExcelUtilAdapter;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
* Type: TableServiceImpl
* Description: serviceImpl
* @author LYM
 */
@Service
public class TableServiceImpl implements ITableService{

    private final ITableRepository tableRepository;
    private final IFieldService fieldService;
    private final IAnswerRecordService answerRecordService;
    private final IAnswerService answerService;
    private final IUserService userService;
    private final ICommentRepository commentRepository;
    private final IUnfilledRecordRepository unfilledTableRepository;

    @Autowired
    public TableServiceImpl(IFieldService fieldService, IAnswerRecordService answerRecordService, IAnswerService answerService, ITableRepository tableRepositry, IUserService userService, ICommentRepository commentRepository, IUnfilledRecordRepository unfilledTableRepository) {
        this.fieldService = fieldService;
        this.answerRecordService = answerRecordService;
        this.answerService = answerService;
        this.tableRepository = tableRepositry;
        this.userService = userService;
        this.commentRepository = commentRepository;
        this.unfilledTableRepository = unfilledTableRepository;
    }

    @Override
    public Table save(Table table) {
        return tableRepository.save(table);
    }

    @Override
    public Table findById(Integer id) {
        return tableRepository.findById(id).orElse(null);
    }

    @Override
    public boolean deleteById(Integer owner, Integer tableId) {
        return tableRepository.deleteByIdAndOwner(tableId, owner) > 0;
    }

    @Override
    public boolean stopById(Integer owner, Integer tableId) {
        return tableRepository.stopByIdAndOwner(tableId, owner) > 0;
    }

    @Override
    public List<Table> findAllActiveTable(Integer owner) {
        return tableRepository.findAllByOwnerAndStateNot(owner, TableStatusEnum.DELETE.getValue());
    }

    @Override
    public List<Table> findAllByExample(Table tableExample) {
        return tableRepository.findAll(Example.of(tableExample));
    }

    @Override
    public List<Table> findAllById(List<Integer> tableIdList) {
        return tableRepository.findAllById(tableIdList);
    }

    @Override
    public boolean increaseFilledNum(Integer tableId) {
        return tableRepository.increaseFilledNum(tableId) > 0;
    }

    /**
     * 表数据导出功能
     * @return 导出结果
     */
    @Override
    public String export() {
        HttpServletRequest request = WebUtil.getRequest();
        HttpSession session = request.getSession();
        User user = (User)session.getAttribute(SessionKey.USER);
        if(user != null){
            int tid = Integer.parseInt(request.getParameter("t"));
            Table table = this.findById(tid);
            if(table != null && table.getOwner().equals(user.getId())){
                Field field = new Field();
                field.setTableId(tid);
                List<Field> fieldList = fieldService.findAll(field);
                AnswerRecord answerRecord = new AnswerRecord();
                answerRecord.setTableId(tid);
                List<AnswerRecord> answerRecordList = answerRecordService.findAll(answerRecord);
                List<AnswerRecordBO> answerRecordBOList = new ArrayList<>();
                Answer answer = new Answer();
                for(AnswerRecord record : answerRecordList){
                    answer.setAnswerRecordId(record.getId());
                    List<Answer> answerList = answerService.findAll(answer);
                    answerList.sort(Comparator.comparing(Answer::getFieldId));
                    AnswerRecordBO answerRecordBO = new AnswerRecordBO(record, answerList);
                    answerRecordBOList.add(answerRecordBO);
                }
                try{
                    String filePath = ExcelUtilAdapter.write(table, fieldList, answerRecordBOList);
                    File file = new File(filePath);
                    FileTransportUtil.downloadFile(file, table.getName() + ".xlsx");
                    //应该缓存，而不是删除********************
                    file.delete();
                    return null;
                }catch (IOException e){
                    e.printStackTrace();
                    return JsonUtil.toJson(ResponseWrapper.fail("excel生成出现错误"));
                }
            }
            return JsonUtil.toJson(ResponseWrapper.fail("权限不足"));
        }
        return JsonUtil.toJson(ResponseWrapper.fail("未登录"));
    }

    /**
     * 根据 tableId 来查询该表所有的信息
     * @param tableId 需要展示的表
     * @param model 设置 model
     */
    public void processTableData(Integer tableId, boolean isOwner,
                                  Model model){
//TODO 增加缓存，组装回复和评论部分待优化。
        // 1. 根据 tableId 找出全部 Field 组成表头
        Field field = new Field();
        field.setTableId(tableId);
        //不是表主人则只能查看可见数据
        if(!isOwner)
            field.setVisibility(true);
        List<Field> fieldList = fieldService.findAll(field);
        if(CollectionUtils.isEmpty(fieldList))
            throw new RuntimeException("没有可见的数据");
        fieldList.sort(Comparator.comparing(Field::getNum));

        // 2. 根据 tableId 找出全部 AnswerRecord 即每一行记录
        AnswerRecord answerRecord = new AnswerRecord();
        answerRecord.setTableId(tableId);
        List<AnswerRecord> answerRecordList = answerRecordService.findAll(answerRecord);
        //表头
        List<String> fieldNameList = new ArrayList<>(fieldList.size());
        List<Integer> fieldIdList = new ArrayList<>(fieldList.size());
        fieldList.forEach( tempField -> {
            fieldNameList.add(tempField.getName());
            fieldIdList.add(tempField.getId());
        });

        // 3. 根据可见字段的id (fieldIdList) 找出所有答案
        List<Answer> allAnswer = answerService.findAllByFieldIds(fieldIdList);
        // 4. 组装成 AnswerRecordBO ，AnswerRecordBO 中包含了每个记录的 答案项
        List<AnswerRecordBO> answerRecordBOList = new ArrayList<>();
        for(AnswerRecord record : answerRecordList){
            List<Answer> answerList = allAnswer.stream()
                    .filter(a -> record.getId().equals(a.getAnswerRecordId()))
                    .sorted(Comparator.comparing(Answer::getFieldId))
                    .collect(Collectors.toList());
            answerRecordBOList.add(new AnswerRecordBO(record, answerList));
        }
        //为了减少时间复杂度 （根据用 fieldId 获取 field）
        Map<Integer, Field> tempMap = new HashMap<>(fieldList.size());
        fieldList.forEach(filed -> tempMap.put(filed.getId(), filed));

        // 5. 处理每个答案记录
        for (AnswerRecordBO answerRecordBO : answerRecordBOList){
            //处理每条 类型为文件的 答案记录
            answerRecordBO.getAnswerList().forEach(
                anw -> anw.setContent(
                    "文件".equals(anw.getType())
                         ?  FilePath.generaterDownloadURL(tableId,
                             tempMap.get(anw.getFieldId()),
                             FileUtil.getFileName(anw.getContent()))
                         : anw.getContent()
                )
            );
        }

        // 6. 查询所有评论
        List<Comment> commentList = commentRepository.findAllByTableIdAndEffective(tableId, true);
        Map<Integer, List<Comment>> replyCommentMap = new HashMap<>(commentList.size());
        List<CommentDTO> commentDTOList = new LinkedList<>();
        List<Integer> userIdList = new ArrayList<>(commentList.size());
        commentList.forEach(comment ->{
            // 组装根评论 DTO，-1 表示根评论
            if(comment.getParentId().equals(-1)){
                commentDTOList.add(new CommentDTO(comment));
            }else{
                //组装 Map< 父评论.Id, 子评论列表>
                List<Comment> replyList = replyCommentMap.get(comment.getParentId());
                if(replyList != null){
                    replyList.add(comment);
                }else {
                    replyList = new LinkedList<>();
                    replyList.add(comment);
                    replyCommentMap.put(comment.getParentId(), replyList);
                }
            }
            //映射 userId 方便查数据库
            userIdList.add(comment.getUserId());
        });
        //查询参与评论的用户的信息
        List<User> userList = userService.findListByIds(userIdList);
        Map<Integer, User> userMap = userList.stream().collect(Collectors.toMap(User::getId, user -> user));
        //组装子评论
        for (CommentDTO commentDTO : commentDTOList) {
            commentDTO.setUserName(userMap.get(commentDTO.getUserId()).getNickname());
            commentDTO.setUserHeadImage(userMap.get(commentDTO.getUserId()).getHeadImage());
            //TODO 未对回复进行排序
            //组装评论
            if(replyCommentMap.get(commentDTO.getId()) != null){
                commentDTO.setReplyList(replyCommentMap.get(commentDTO.getId()).stream()
                        .map(comment -> {
                            CommentDTO dto = new CommentDTO(comment);
                            dto.setUserHeadImage(userMap.get(comment.getUserId()).getHeadImage());
                            dto.setUserName(userMap.get(comment.getUserId()).getNickname());
                            return dto;
                        }).collect(Collectors.toList()));
            }
        }
        // 添加 model

        model.addAttribute("headers", fieldNameList);
        model.addAttribute("answerRecords", answerRecordBOList);
        model.addAttribute("commentList", commentDTOList);
    }

    /**
     * 搜索功能
     * @param tableName 表名
     * @param state 表状态
     * @return
     */
    @Override
    public List<Table> searchByNameAndState(String tableName, TableStatusEnum state){
        //TODO 需要限制查询结果个数
        return tableRepository.findAllByStateAndNameContaining(state.getValue(), tableName);
    }

    @Override
    public Page<Table> getPageTable(int pageNumber, int pageSize){
        PageRequest request = this.buildPageRequest(pageNumber,pageSize);
        return tableRepository.findAll(request);
    }

    //构建PageRequest
    private PageRequest buildPageRequest(int pageNumber, int pageSize) {
        return PageRequest.of(pageNumber - 1, pageSize);
    }

    //组装tableDTO
    public List<TableDTO> constructTableDTO(List<Table> tableList){
        List<Integer> ownerIdList = tableList.stream().map(Table::getOwner).collect(Collectors.toList());
        List<User> userList = userService.findListByIds(ownerIdList);
        Map<Integer, String> userNameMap = userList.stream().collect(Collectors.toMap(User::getId, User::getNickname));
        List<TableDTO> tableDTOList = new ArrayList<>(tableList.size());
        tableList.forEach(table -> {
            TableDTO dto = new TableDTO(table);
            dto.setOwnerName(userNameMap.get(table.getOwner()));
            tableDTOList.add(dto);
        });
        return tableDTOList;
    }

    @Override
    public void addLikeTable(Integer tableId) {
        HttpServletRequest request = WebUtil.getRequest();
        HttpSession session = request.getSession();
        User user =(User)session.getAttribute(SessionKey.USER);
        UnfilledRecord record = new UnfilledRecord();
        record.setTableId(tableId);
        record.setUserId(user.getId());
        record.setDelete(false);
        record.setFilled(false);
        record.setCreateTime(new Date());
        synchronized (session){
            UnfilledRecord dbRecord = unfilledTableRepository.findByUserIdAndTableId(user.getId(), tableId);
            if(dbRecord == null){
                unfilledTableRepository.save(record);
            }else if(dbRecord.getDelete()){
                dbRecord.setDelete(false);
                unfilledTableRepository.save(dbRecord);
            }
        }
    }

    @Override
    public void save(List<Table> endTableList) {
        tableRepository.saveAll(endTableList);
    }
}