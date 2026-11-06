package com.hmdp.utils;


import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("GET".equalsIgnoreCase(request.getMethod()) &&
                (path.matches("/shop/[0-9]+") || path.equals("/shop/of/type") || path.equals("/shop/of/name")
                        || path.matches("/voucher/list/[0-9]+") || path.equals("/shop-type/list"))) {
            return true;
        }
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }

}
