package com.shizuku.rwmiao.module.script;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public final class ScriptSetting {
    public static final String BOOLEAN="boolean", NUMBER="number", CHOICE="choice", TEXT="text";
    public static final String SWITCH="switch", SLIDER="slider", INPUT="input", CHOICE_COMPONENT="choice";
    public final String key, name, description, type, component, defaultValue;
    public final Double min, max, step;
    public final List<Option> options;

    public static final class Option {
        public final String value, label;
        public Option(String value,String label){this.value=value;this.label=label;}
    }

    ScriptSetting(String key,String name,String description,String type,String component,String defaultValue,
                  Double min,Double max,Double step,List<Option> options)throws IOException{
        if(key==null||!key.matches("[A-Za-z][A-Za-z0-9_.-]{0,47}"))throw new IOException("设置 key 格式无效: "+key);
        this.key=key;this.name=name==null||name.trim().isEmpty()?key:name.trim();
        this.description=description==null?"":description.trim();this.type=type==null?TEXT:type.toLowerCase();
        if(!BOOLEAN.equals(this.type)&&!NUMBER.equals(this.type)&&!CHOICE.equals(this.type)&&!TEXT.equals(this.type))
            throw new IOException("设置 "+key+" 的 type 仅支持 boolean/number/choice/text");
        String ui=component==null?null:component.toLowerCase();
        if(ui==null||ui.isEmpty())ui=BOOLEAN.equals(this.type)?SWITCH:CHOICE.equals(this.type)?CHOICE_COMPONENT:INPUT;
        if(!SWITCH.equals(ui)&&!SLIDER.equals(ui)&&!INPUT.equals(ui)&&!CHOICE_COMPONENT.equals(ui))throw new IOException("设置 "+key+" 的 component 仅支持 switch/slider/input");
        if(SWITCH.equals(ui)&&!BOOLEAN.equals(this.type))throw new IOException("switch 组件必须使用 boolean 类型: "+key);
        if(SLIDER.equals(ui)&&!NUMBER.equals(this.type))throw new IOException("slider 组件必须使用 number 类型: "+key);
        if(INPUT.equals(ui)&&!NUMBER.equals(this.type)&&!TEXT.equals(this.type))throw new IOException("input 组件的 value_type 仅支持 text/number: "+key);
        if(SLIDER.equals(ui)&&(min==null||max==null||max<=min))throw new IOException("slider 必须声明有效的 min/max: "+key);
        this.component=ui;this.min=min;this.max=max;this.step=step;this.options=Collections.unmodifiableList(options);
        if(CHOICE.equals(this.type)&&options.isEmpty())throw new IOException("choice 设置必须声明 options: "+key);
        String fallback=defaultValue;
        if(fallback==null){if(BOOLEAN.equals(this.type))fallback="false";else if(NUMBER.equals(this.type))fallback="0";else if(CHOICE.equals(this.type))fallback=options.get(0).value;else fallback="";}
        this.defaultValue=normalize(fallback);
    }

    public String normalize(String value)throws IOException{
        String v=value==null?defaultValue:value;
        if(BOOLEAN.equals(type))return Boolean.toString(Boolean.parseBoolean(v));
        if(NUMBER.equals(type)){
            double n;try{n=Double.parseDouble(v);}catch(Throwable t){throw new IOException("设置 "+key+" 不是数字");}
            if(min!=null)n=Math.max(min,n);if(max!=null)n=Math.min(max,n);
            return Double.toString(n);
        }
        if(CHOICE.equals(type)){for(Option option:options)if(option.value.equals(v))return v;throw new IOException("设置 "+key+" 的值不在 options 中");}
        return v.length()>256?v.substring(0,256):v;
    }
}
