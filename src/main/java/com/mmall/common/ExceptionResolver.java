package com.mmall.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.json.MappingJacksonJsonView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Slf4j
@Component
//习惯@Repository用在DAO层，Service用在Service层上，对于非这两层的注解都用Component，它们效果都一样，代表这玩意是spring容器的bean
public class ExceptionResolver implements HandlerExceptionResolver{

    @Override
    public ModelAndView resolveException(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object o, Exception e) {
        //这行一定要啊，不然我们的异常都丢失了
        log.error("{} Exception",httpServletRequest.getRequestURI(),e);
        //项目是前后端分离的，不需要转成一个view，把ModelAndView转成Json ModelAndView
        ModelAndView modelAndView = new ModelAndView(new MappingJacksonJsonView());

        //当使用是jackson2.x的时候使用MappingJackson2JsonView，这里使用的是1.9。
        modelAndView.addObject("status",ResponseCode.ERROR.getCode());
        modelAndView.addObject("msg","接口异常,详情请查看服务端日志的异常信息");
        //非常简明扼要的异常信息
        modelAndView.addObject("data",e.toString());
        return modelAndView;
    }

}
