package io.choerodon.notify.api.service.impl;

import io.choerodon.core.exception.CommonException;
import io.choerodon.core.iam.ResourceLevel;
import io.choerodon.notify.api.dto.OrganizationProjectDTO;
import io.choerodon.notify.api.dto.ReceiveSettingDTO;
import io.choerodon.notify.api.service.ReceiveSettingService;
import io.choerodon.notify.api.validator.CommonValidator;
import io.choerodon.notify.domain.ReceiveSetting;
import io.choerodon.notify.domain.SendSetting;
import io.choerodon.notify.infra.feign.UserFeignClient;
import io.choerodon.notify.infra.mapper.ReceiveSettingMapper;
import io.choerodon.notify.infra.mapper.SendSettingMapper;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author dengyouquan
 **/
@Component
public class ReceiveSettingServiceImpl implements ReceiveSettingService {
    private final ReceiveSettingMapper receiveSettingMapper;
    private final SendSettingMapper sendSettingMapper;
    private final UserFeignClient userFeignClient;
    private final ModelMapper modelMapper = new ModelMapper();

    public ReceiveSettingServiceImpl(ReceiveSettingMapper receiveSettingMapper, SendSettingMapper sendSettingMapper, UserFeignClient userFeignClient) {
        this.receiveSettingMapper = receiveSettingMapper;
        this.sendSettingMapper = sendSettingMapper;
        this.userFeignClient = userFeignClient;
        modelMapper.addMappings(ReceiveSettingDTO.entity2Dto());
        modelMapper.addMappings(ReceiveSettingDTO.dto2Entity());
        modelMapper.validate();
    }

    @Override
    public List<ReceiveSettingDTO> queryByUserId(final Long userId) {
        ReceiveSetting receiveSetting = new ReceiveSetting();
        receiveSetting.setUserId(userId);
        return modelMapper.map(receiveSettingMapper.select(receiveSetting), List.class);
    }

    @Override
    @Transactional
    public void update(final Long userId, final List<ReceiveSettingDTO> settingDTOList) {
        if (userId == null) return;
        //没有校验接收通知设置中，层级是否一致，
        // 即sendSettingId中对应的level和接收通知设置中level不一定一致
        List<ReceiveSetting> updateSettings = settingDTOList.stream().
                map(settingDTO -> modelMapper.map(settingDTO, ReceiveSetting.class))
                .peek(receiveSetting -> receiveSetting.setUserId(userId)).collect(Collectors.toList());
        ReceiveSetting receiveSetting = new ReceiveSetting();
        receiveSetting.setUserId(userId);
        List<ReceiveSetting> dbSettings = receiveSettingMapper.select(receiveSetting);
        //备份updateSettings，移除updateSettings和数据库dbSettings中不同的元素
        List<ReceiveSetting> insertSetting = new ArrayList<>(updateSettings);
        insertSetting.removeAll(dbSettings);
        //insertSetting是应该插入的元素
        insertSetting.forEach(t -> {
            t.setUserId(userId);
            if (receiveSettingMapper.insert(t) != 1) {
                throw new CommonException("error.receiveSetting.update");
            }
        });
        //移除数据库dbSettings和updateSettings中不同的元素，这些是应该删除的对象
        dbSettings.removeAll(updateSettings);
        dbSettings.forEach(t -> {
            t.setUserId(userId);
            if (receiveSettingMapper.delete(t) != 1) {
                throw new CommonException("error.receiveSetting.update");
            }
        });
    }


    @Override
    public void updateByUserIdAndSourceTypeAndSourceId(final Long userId, final String sourceType, final Long sourceId, final String messageType, final boolean disable) {
        if (userId == null) return;
        CommonValidator.validatorLevel(sourceType);
        /*
            没有校验sourceId是否存在，需要发feign，对性能有影响，因此不校验
            1. 前端不会发不存在的sourceId过来
            2. 如果是postman等方式发送不存在的sourceId过来也不会有其他问题，只是数据有脏数据而已
         */
        if (!disable) {
            //如果不是禁用的话，从数据库删除记录
            receiveSettingMapper.deleteByUserIdAndSourceTypeAndSourceId(userId, sourceType, sourceId);
        } else {
            //如果是禁用，则不需要接收通知，向数据库插入记录
            SendSetting query = new SendSetting();
            query.setLevel(sourceType);
            query.setAllowConfig(true);
            sendSettingMapper.select(query).forEach(sendSetting -> {
                ReceiveSetting receiveSetting = new ReceiveSetting(sendSetting.getId(), messageType, sourceId, sourceType, userId);
                if (receiveSettingMapper.selectCount(receiveSetting) == 0) {
                    receiveSettingMapper.insert(receiveSetting);
                }
            });
        }
    }

    @Override
    public void updateByUserId(final Long userId, final String messageType, final boolean disable) {
        if (userId == null) return;
        if (!disable) {
            //如果不是禁用的话，从数据库删除该用户全部记录
            receiveSettingMapper.deleteByUserIdAndSourceTypeAndSourceId(userId, null, null);
        } else {
            //如果是禁用，则不需要接收通知，向数据库插入记录
            SendSetting query = new SendSetting();
            query.setAllowConfig(true);
            //feign调用，从iam-service查询用户所在所有项目和组织
            final OrganizationProjectDTO organizationProjectDTO =
                    userFeignClient.queryByUserIdOrganizationProject(userId).getBody();
            sendSettingMapper.select(query).forEach(sendSetting -> {
                if (sendSetting.getLevel().equalsIgnoreCase(ResourceLevel.SITE.value())) {
                    insertReceiveSettingSite(userId, messageType, sendSetting);
                } else if (sendSetting.getLevel().equalsIgnoreCase(ResourceLevel.ORGANIZATION.value())) {
                    //遍历组织，插入记录
                    insertReceiveSettingOrganization(userId, messageType, organizationProjectDTO, sendSetting);
                } else if (sendSetting.getLevel().equalsIgnoreCase(ResourceLevel.PROJECT.value())) {
                    //遍历项目，插入记录
                    insertReceiveSettingProject(userId, messageType, organizationProjectDTO, sendSetting);
                }
            });
        }
    }

    private void insertReceiveSettingSite(Long userId, String messageType, SendSetting sendSetting) {
        ReceiveSetting receiveSetting =
                new ReceiveSetting(sendSetting.getId(), messageType, 0L, ResourceLevel.SITE.value(), userId);
        //判断数据库是否有记录,没有则插入记录
        if (receiveSettingMapper.selectCount(receiveSetting) == 0) {
            receiveSettingMapper.insert(receiveSetting);
        }
    }

    private void insertReceiveSettingOrganization(Long userId, String messageType, OrganizationProjectDTO organizationProjectDTO, SendSetting sendSetting) {
        organizationProjectDTO.getOrganizationList().forEach(organization -> {
            ReceiveSetting receiveSetting =
                    new ReceiveSetting(sendSetting.getId(), messageType, organization.getId(), ResourceLevel.ORGANIZATION.value(), userId);
            if (receiveSettingMapper.selectCount(receiveSetting) == 0) {
                receiveSettingMapper.insert(receiveSetting);
            }
        });
    }

    private void insertReceiveSettingProject(Long userId, String messageType, OrganizationProjectDTO organizationProjectDTO, SendSetting sendSetting) {
        organizationProjectDTO.getProjectList().forEach(project -> {
            ReceiveSetting receiveSetting =
                    new ReceiveSetting(sendSetting.getId(), messageType, project.getId(), ResourceLevel.PROJECT.value(), userId);
            if (receiveSettingMapper.selectCount(receiveSetting) == 0) {
                receiveSettingMapper.insert(receiveSetting);
            }
        });
    }
}
