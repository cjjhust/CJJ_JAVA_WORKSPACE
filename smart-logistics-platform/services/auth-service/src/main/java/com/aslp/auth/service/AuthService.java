package com.aslp.auth.service;

import com.aslp.auth.entity.AuthUser;
import com.aslp.auth.repository.AuthUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * P2-6 — 登录认证（真实用户存储）。
 *
 * <p><b>替代原演示实现</b>：原 {@code AuthController.login} 不做任何密码校验、
 * 按「用户名以 admin 开头」推导角色 —— 任何用户名都能拿到令牌，
 * {@code adminXYZ} 甚至直接是管理员。现在：
 * <ul>
 *   <li>用户必须存在于 {@code auth_user} 表（Flyway 种子注入 admin/operator）；</li>
 *   <li>密码必须通过 BCrypt 校验（哈希含盐、不可逆，库里没有明文）；</li>
 *   <li>角色从用户行读取（数据决定 RBAC，代码里不再有任何 {@code startsWith("admin")}）。</li>
 * </ul>
 *
 * <p><b>防用户枚举</b>：「用户不存在」与「密码错误」返回同一结果（null → 401），
 * 不泄露哪些用户名是注册的。BCrypt 校验是常数时间成本大头，
 * 用户不存在时同样跑一次固定成本的假哈希比对，抹平时序差异
 * （生产进一步做法是接限流 —— 网关侧 {@code ipKeyResolver} 已按 IP 限流认证入口）。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * 预生成的假哈希（登录时用户不存在则用它跑一次比对，抹平「存在/不存在」的耗时差异）。
     * 值本身无意义，只要是一次合法的 BCrypt 编码结果即可。
     */
    private static final String DUMMY_HASH =
            "$2a$10$vKBU4b410MkTXiXVtfnf8.L0ypsqlJNby8BP8NgC/qGZBtnzQVW32";

    private final AuthUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(AuthUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 校验用户名/密码。
     *
     * @return 用户（含角色）；认证失败（用户不存在或密码错误）返回 {@link Optional#empty()}
     */
    public Optional<AuthUser> authenticate(String username, String password) {
        Optional<AuthUser> user = userRepository.findByUsername(username);
        boolean ok;
        if (user.isPresent()) {
            ok = passwordEncoder.matches(password, user.get().getPasswordHash());
        } else {
            // 用户不存在：对固定假哈希跑一次比对，时序与「存在但密码错」一致
            ok = passwordEncoder.matches(password, DUMMY_HASH);
        }
        if (!ok) {
            // 不区分「用户不存在 / 密码错误」—— 防止用户名枚举
            log.info("[认证] 登录失败：username={}", username);
            return Optional.empty();
        }
        log.info("[认证] 登录成功：username={} role={}", username, user.get().getRole());
        return user;
    }

    /** 用户角色 → JWT roles 列表（ADMIN 同时带 USER 语义，兼容网关权限映射）。 */
    public static List<String> rolesOf(AuthUser user) {
        return "ADMIN".equals(user.getRole()) ? List.of("ADMIN", "USER") : List.of(user.getRole());
    }
}
