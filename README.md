# frp-Android

<div style="display:inline-block">
<img src="./image/image1.png" alt="image1.png" width="200">
<img src="./image/image2.png" alt="image2.png" width="200">
</div>

## 编译方法

如果您想自定义frp内核，可以通过Github Actions或通过Android Studio编译

## 常见问题
### 项目的frp内核(libfrpc.so)是怎么来的？
直接从[frp的release](https://github.com/fatedier/frp/releases)里把对应ABI的Linux版本压缩包解压之后重命名frpc为libfrpc.so  
项目不是在代码里调用so中的方法，而是把so作为一个可执行文件，然后通过shell去执行对应的命令  
因为Golang的零依赖特性，所以可以直接在Android里通过shell运行可执行文件

### 开机自启与后台保活
按照原生Android规范设计，如有问题请在系统设置内允许开机自启/后台运行相关选项
