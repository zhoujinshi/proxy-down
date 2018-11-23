package main

import (
	"common"
	"handles"
	"net/http"
	"strconv"
)

func main() {
	http.HandleFunc("/version/checkUpdate", handles.CheckUpdate)
	http.HandleFunc("/extension/webhook", handles.WebHook)
	http.HandleFunc("/extension/search", handles.Search)
	http.HandleFunc("/extension/checkExtensionUpdate", handles.CheckExtensionUpdate)
	http.HandleFunc("/extension/down", handles.Down)
	http.HandleFunc("/recommend/soft", handles.RecommendSoft)
	http.HandleFunc("/private/bdyResolve", handles.BdyResolve)
	http.ListenAndServe(":"+strconv.Itoa(common.Config.Port), nil)
}
