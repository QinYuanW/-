package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.iot.platform.common.BizException;
import com.iot.platform.common.ErrorCode;
import com.iot.platform.dto.DeviceGroupRequest;
import com.iot.platform.entity.Device;
import com.iot.platform.entity.DeviceGroup;
import com.iot.platform.mapper.DeviceGroupMapper;
import com.iot.platform.mapper.DeviceMapper;
import com.iot.platform.service.DeviceGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * E4 设备分组服务实现。
 *
 * <p>两条关键设计：
 * <ol>
 *   <li><b>删分组不删设备</b> —— 组内设备 {@code group_id} 置空回到「未分组」，
 *       避免"手滑删个分组把设备一起干掉"的运维事故；</li>
 *   <li><b>批量分配走一条 UPDATE</b>（{@code IN (deviceKeys)}），不做逐台更新。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceGroupServiceImpl implements DeviceGroupService {

    private final DeviceGroupMapper groupMapper;
    private final DeviceMapper deviceMapper;

    @Override
    public List<Map<String, Object>> listWithCount() {
        return groupMapper.selectWithDeviceCount();
    }

    @Override
    public long countUngrouped() {
        return groupMapper.countUngrouped();
    }

    @Override
    public Long create(DeviceGroupRequest request) {
        String name = trim(request.getName());
        if (name == null) throw new BizException(ErrorCode.PARAM_ERROR, "分组名称不能为空");
        if (existsByName(name, null)) throw new BizException(ErrorCode.PARAM_ERROR, "分组名称已存在：" + name);

        DeviceGroup g = new DeviceGroup();
        g.setName(name);
        g.setDescription(trim(request.getDescription()));
        g.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        groupMapper.insert(g);
        log.info("新建分组[{}] id={}", name, g.getId());
        return g.getId();
    }

    @Override
    public void update(Long id, DeviceGroupRequest request) {
        DeviceGroup exist = groupMapper.selectById(id);
        if (exist == null) throw new BizException(ErrorCode.PARAM_ERROR, "分组不存在");

        String name = trim(request.getName());
        if (name == null) throw new BizException(ErrorCode.PARAM_ERROR, "分组名称不能为空");
        if (existsByName(name, id)) throw new BizException(ErrorCode.PARAM_ERROR, "分组名称已存在：" + name);

        DeviceGroup g = new DeviceGroup();
        g.setId(id);
        g.setName(name);
        g.setDescription(trim(request.getDescription()));
        g.setSortOrder(request.getSortOrder() == null ? exist.getSortOrder() : request.getSortOrder());
        groupMapper.updateById(g);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        DeviceGroup exist = groupMapper.selectById(id);
        if (exist == null) throw new BizException(ErrorCode.PARAM_ERROR, "分组不存在");

        // 先让组内设备回到「未分组」，再删分组本身 —— 不级联删设备
        int freed = deviceMapper.update(null, new LambdaUpdateWrapper<Device>()
                .eq(Device::getGroupId, id)
                .set(Device::getGroupId, null));

        // 墓碑写 id（而非 1）：配合 uk_group_name(name, deleted)，让名称可被**反复**复用。
        // 必须手工 UPDATE —— MP 的 deleteById 会写死成 delval，第二次删同名组仍会撞唯一键（实测 1062）。
        groupMapper.update(null, new UpdateWrapper<DeviceGroup>()
                .eq("id", id)
                .set("deleted", id));

        log.info("删除分组[{}]（id={}），{} 台设备回到未分组", exist.getName(), id, freed);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int assignDevices(List<String> deviceKeys, Long groupId) {
        if (deviceKeys == null || deviceKeys.isEmpty()) return 0;

        if (groupId == null || groupId == 0L) {
            groupId = null;   // 0 视同移出分组
        } else if (groupMapper.selectById(groupId) == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "目标分组不存在");
        }

        int n = deviceMapper.update(null, new LambdaUpdateWrapper<Device>()
                .in(Device::getDeviceKey, deviceKeys)
                .set(Device::getGroupId, groupId));
        log.info("批量分配：{} 台设备 → 分组 {}", n, groupId == null ? "(未分组)" : groupId);
        return n;
    }

    private boolean existsByName(String name, Long excludeId) {
        LambdaQueryWrapper<DeviceGroup> q = new LambdaQueryWrapper<DeviceGroup>()
                .eq(DeviceGroup::getName, name);
        if (excludeId != null) q.ne(DeviceGroup::getId, excludeId);
        return groupMapper.selectCount(q) > 0;
    }

    private String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
