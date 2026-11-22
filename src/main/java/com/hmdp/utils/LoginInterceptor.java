package com.hmdp.utils;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author tianchi
 */
public class LoginInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;
    public LoginInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.判断是否要进行拦截(ThreadLocal中是否有用户)
        if(UserHolder.getUser() == null){
            //没有,需要拦截,设置状态码
            response.setStatus(401);
            //拦截
            return false;
        }
        //let go
        return true;
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //后置拦截器
        UserHolder.removeUser();
    }
}
