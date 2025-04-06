package com.keji.green.lit.engine.service.impl;

import com.keji.green.lit.engine.dao.UserDao;
import com.keji.green.lit.engine.dto.response.UserResponse;
import com.keji.green.lit.engine.enums.UserStatusEnum;
import com.keji.green.lit.engine.exception.BusinessException;
import com.keji.green.lit.engine.model.User;
import com.keji.green.lit.engine.service.UserService;
import com.keji.green.lit.engine.service.VerificationCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;

import java.util.Optional;
import java.util.regex.Pattern;

import static com.keji.green.lit.engine.exception.ErrorCode.*;

/**
 * 用户服务实现类
 * 实现用户管理相关的业务逻辑
 * 同时实现UserDetailsService接口以支持Spring Security认证
 * @author xiangjun_lee
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService, UserDetailsService {

    /**
     * 用户数据访问层
     */
    @Resource
    private UserDao userDao;

    /**
     * 验证码服务
     */
    @Resource
    private VerificationCodeService verificationCodeService;

    /**
     * 密码编码器
     */
    @Resource
    private PasswordEncoder passwordEncoder;

    /**
     * 手机号正则表达式
     * 匹配中国大陆手机号
     */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    /**
     * 用户注册
     *
     * @param user 注册请求，包含手机号、验证码和密码
     * @throws BusinessException 注册失败，请联系管理员
     */
    @Override
    @Transactional
    public void saveUser(User user) {
        // 创建用户
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        if (userDao.createUser(user) <= 0) {
            throw new BusinessException(DATABASE_WRITE_ERROR.getCode(), "注册失败，请联系管理员");
        }
    }

    /**
     * 注销账号
     * 将用户状态设置为非活跃
     *
     * @param uid 用户ID
     * @throws BusinessException 用户不存在时抛出
     */
    @Override
    @Transactional
    public void deactivateAccount(Long uid) {
        User user = userDao.findById(uid)
                .orElseThrow(() -> new BusinessException(USER_NOT_EXIST.getCode(),"用户不存在"));
        userDao.updateUserStatus(user.getUid(), UserStatusEnum.CANCELLED.getCode());
    }

    /**
     * 获取当前登录用户信息
     *
     * @return 用户信息响应
     * @throws BusinessException 用户未登录或不存在时抛出
     */
    @Override
    public UserResponse getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(UNAUTHORIZED.getCode(), "用户未登录");
        }

        String phone = authentication.getName();
        User user = userDao.findByPhone(phone)
                .orElseThrow(() -> new BusinessException(USER_NOT_EXIST.getCode(),"用户不存在"));

        return UserResponse.fromUser(user);
    }


    /**
     * 检查手机号是否已注册且账号处于活跃状态
     *
     * @param phone 手机号
     * @return true表示已注册且活跃，false表示未注册或已注销
     */
    @Override
    public boolean isPhoneRegisteredAndActive(String phone) {
        // 验证手机号格式
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException(PARAM_ERROR.getCode(), "手机号格式不正确");
        }
        // 查询用户信息
        Optional<User> userOptional = userDao.findByPhone(phone);
        if (userOptional.isEmpty()) {
            return false;
        }
        // 检查用户状态
        User user = userOptional.get();
        return UserStatusEnum.isNormal(user.getStatus());
    }

    @Override
    public User queryNormalUserByPhone(String phone) {
        Optional<User> userOptional = userDao.findByPhone(phone);
        if (userOptional.isEmpty()) {
            throw new BusinessException(USER_NOT_EXIST.getCode(), "用户不存在");
        }
        User user = userOptional.get();
        if (UserStatusEnum.isCancelled(user.getStatus())) {
            throw new BusinessException(USER_NOT_EXIST.getCode(), "用户已注销");
        }
        if (!UserStatusEnum.isNormal(user.getStatus())) {
            throw new BusinessException(AUTH_ERROR.getCode(), "该账户异常，请联系管理员");
        }
        return user;
    }

    @Override
    public int resetPasswordByUid(Long uid, String newPassword, Integer version) {
        User user = new User();
        user.setUid(uid);
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setVersion(version);
        return userDao.updateSelectiveByUid(user);

    }

    /**
     * 根据用户名（手机号）加载用户详情
     * 实现UserDetailsService接口的方法，用于Spring Security认证
     *
     * @param username 用户名（手机号）
     * @return 用户详情
     * @throws UsernameNotFoundException 用户不存在时抛出
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userDao.findByPhone(username)
                .orElseThrow(() -> new UsernameNotFoundException("手机号未注册: " + username));
    }
} 