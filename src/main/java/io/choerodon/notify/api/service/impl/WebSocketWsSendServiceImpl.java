package io.choerodon.notify.api.service.impl;

import freemarker.template.TemplateException;
import io.choerodon.core.exception.CommonException;
import io.choerodon.notify.api.dto.UserDTO;
import io.choerodon.notify.api.pojo.DefaultAutowiredField;
import io.choerodon.notify.api.pojo.PmType;
import io.choerodon.notify.api.service.WebSocketSendService;
import io.choerodon.notify.domain.SendSetting;
import io.choerodon.notify.domain.SiteMsgRecord;
import io.choerodon.notify.domain.Template;
import io.choerodon.notify.infra.mapper.SiteMsgRecordMapper;
import io.choerodon.notify.infra.mapper.TemplateMapper;
import io.choerodon.notify.websocket.send.MessageSender;
import io.choerodon.notify.websocket.send.WebSocketSendPayload;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service("pmWsSendService")
public class WebSocketWsSendServiceImpl implements WebSocketSendService {
    public static final String MSG_TYPE_PM = "site-msg";

    private final TemplateRender templateRender;

    private final TemplateMapper templateMapper;

    private final SiteMsgRecordMapper siteMsgRecordMapper;

    private final MessageSender messageSender;


    public WebSocketWsSendServiceImpl(TemplateRender templateRender,
                                      TemplateMapper templateMapper,
                                      MessageSender messageSender,
                                      SiteMsgRecordMapper siteMsgRecordMapper) {
        this.templateRender = templateRender;
        this.templateMapper = templateMapper;
        this.messageSender = messageSender;
        this.siteMsgRecordMapper = siteMsgRecordMapper;
    }

    @Override
    public void sendSiteMessage(String code, Map<String, Object> params, Set<UserDTO> targetUsers, Long sendBy, SendSetting sendSetting) {
        Template template = templateMapper.selectByPrimaryKey(sendSetting.getPmTemplateId());
        validatorPmTemplate(template);
        List<SiteMsgRecord> records = new LinkedList<>();
        targetUsers.forEach(user -> {
            try {
                //userId为空，则是email没有对应的id，因此不发送站内信
                //oracle可以批量插入超过1000条,但是超过太多则耗时长
                if (records.size() >= 999) {
                    siteMsgRecordMapper.batchInsert(records);
                    records.clear();
                }
                if (user.getId() != null) {
                    Map<String, Object> userParams = DefaultAutowiredField.autowiredDefaultParams(params, user);
                    String pmContent = templateRender.renderTemplate(template, userParams, TemplateRender.TemplateType.CONTENT);
                    String pmTitle = templateRender.renderTemplate(template, userParams, TemplateRender.TemplateType.TITLE);
                    SiteMsgRecord record = new SiteMsgRecord(user.getId(), pmTitle, pmContent);
                    record.setSendBy(sendBy);
                    if (PmType.NOTICE.getValue().equals(sendSetting.getPmType())) {
                        record.setType(PmType.NOTICE.getValue());
                    }
                    records.add(record);
                }
            } catch (IOException | TemplateException e) {
                throw new CommonException("error.templateRender.renderError", e);
            }
        });
        siteMsgRecordMapper.batchInsert(records);
        records.clear();
        //是否即时发送
        if (sendSetting.getIsSendInstantly() != null && sendSetting.getIsSendInstantly()) {
            targetUsers.forEach(user -> {
                String key = "choerodon:msg:site-msg:" + user.getId();
                messageSender.sendByKey(key, new WebSocketSendPayload<>(MSG_TYPE_PM, key, siteMsgRecordMapper.selectCountOfUnRead(user.getId())));
            });
        }
    }

    private void validatorPmTemplate(Template template) {
        if (template == null) {
            throw new CommonException("error.pmTemplate.notExist");
        }
        if (template.getPmContent() == null) {
            throw new CommonException("error.pmTemplate.contentNull");
        }
    }

    @Override
    public void sendWebSocket(String code, String id, String message) {
        String key = "choerodon:msg:" + code + ":" + id;
        messageSender.sendByKey(key, code, message);
    }
}
