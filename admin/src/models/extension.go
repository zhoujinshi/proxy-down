package models

import (
	"common"
	"database/sql"
	_ "github.com/go-sql-driver/mysql"
	"math"
	"strings"
	"time"
)

type Extension struct {
	Id      int64   `json:"id"`
	Title   string  `json:"title"`
	Version float64 `json:"version"`
	Require struct {
		Min float64 `json:"min"`
		Max float64 `json:"max"`
	} `json:"require"`
	Homepage    string    `json:"homepage"`
	Description string    `json:"description"`
	Path        string    `json:"path"`
	Files       string    `json:"files"`
	CreateTime  time.Time `json:"createTime"`
	UpdateTime  time.Time `json:"updateTime"`
}

type ExtensionCheck struct {
	Version float64 `json:"version"`
	Path    string  `json:"path"`
}

func SelectExtensionByPath(path string) (*Extension, error) {
	db, err := common.GetDb()
	defer db.Close()
	if err != nil {
		return nil, err
	}
	rows, err := db.Query("select id,version from extension where path = ?", path)
	defer rows.Close()
	if err != nil {
		return nil, err
	}
	if rows.Next() {
		var extension Extension
		err = rows.Scan(&extension.Id, &extension.Version)
		if err != nil {
			return nil, err
		} else {
			return &extension, nil
		}
	}
	return nil, nil
}

func SelectExtensionByKeyword(keyword string, pdVersion float64, pageNum int, pageSize int) (*Page, error) {
	page := Page{PageNum: pageNum, PageSize: pageSize}
	db, err := common.GetDb()
	defer db.Close()
	if err != nil {
		return nil, err
	}
	var where = " where (require_min <= 0 or require_min <= ? ) and (require_max <=0 or require_max >= ? )"
	var params = []interface{}{pdVersion, pdVersion}
	if len(strings.TrimSpace(keyword)) > 0 {
		where = " and (title like CONCAT('%',?,'%') or description like CONCAT('%',?,'%'))"
		params = append(params, []interface{}{keyword, keyword}...)
	}

	stmt, err := db.Prepare("select count(*) from extension" + where)
	defer stmt.Close()
	if err != nil {
		return nil, err
	}
	var rows *sql.Rows
	var queryErr error
	if len(params) != 0 {
		rows, queryErr = stmt.Query(params...)
	} else {
		rows, queryErr = stmt.Query()
	}
	defer rows.Close()
	if queryErr != nil {
		return nil, queryErr
	}
	if rows.Next() {
		var count int
		err = rows.Scan(&count)
		if err != nil {
			return nil, err
		} else {
			page.TotalCount = count
			page.TotalPage = int(math.Ceil(float64(count) / float64(pageSize)))
		}
	}

	stmt, err = db.Prepare("select id,title,version,homepage,description,path,files,create_time,update_time from extension" + where + " limit ?,?")
	defer stmt.Close()
	if err != nil {
		return nil, err
	}
	params = append(params, []interface{}{(pageNum - 1) * pageSize, pageSize}...)
	if len(params) != 0 {
		rows, queryErr = stmt.Query(params...)
	} else {
		rows, queryErr = stmt.Query()
	}
	defer rows.Close()
	if queryErr != nil {
		return nil, queryErr
	}
	if err != nil {
		return nil, err
	}
	page.Data = []interface{}{}
	for rows.Next() {
		var extension Extension
		err = rows.Scan(&extension.Id, &extension.Title, &extension.Version, &extension.Homepage, &extension.Description, &extension.Path, &extension.Files, &extension.CreateTime, &extension.UpdateTime)
		if err != nil {
			return nil, err
		} else {
			page.Data = append(page.Data, extension)
		}
	}
	return &page, nil
}

func CheckExtensionUpdate(extensionChecks []ExtensionCheck) (*[]Extension, error) {
	db, err := common.GetDb()
	defer db.Close()
	if err != nil {
		return nil, err
	}
	var where = ""
	var params []interface{}
	if len(extensionChecks) > 0 {
		where = " where "
		for index, check := range extensionChecks {
			params = append(params, []interface{}{check.Path, check.Version}...)
			if index != 0 {
				where += "or"
			}
			where += "(path = ? and version > ?)"
		}
	}
	stmt, err := db.Prepare("select path,version from extension" + where)
	defer stmt.Close()
	if err != nil {
		return nil, err
	}
	rows, err := stmt.Query(params...)
	defer rows.Close()
	if err != nil {
		return nil, err
	}
	var extensions []Extension
	for rows.Next() {
		var extension Extension
		err = rows.Scan(&extension.Path, &extension.Version)
		if err != nil {
			return nil, err
		} else {
			extensions = append(extensions, extension)
		}
	}
	return &extensions, nil
}

func (extension *Extension) Update() {
	db, err := common.GetDb()
	defer db.Close()
	if err != nil {
		return
	}
	stmt, err := db.Prepare("update extension set title=?,version=?,require_min=?,require_max=?,homepage=?,description=?,files=?,update_time=? where id=?")
	defer stmt.Close()
	if err != nil {
		return
	}
	stmt.Exec(extension.Title, extension.Version, extension.Require.Min, extension.Require.Max, extension.Homepage, extension.Description, extension.Files, extension.UpdateTime, extension.Id)
}

func (extension *Extension) Insert() {
	db, err := common.GetDb()
	defer db.Close()
	if err != nil {
		return
	}
	stmt, err := db.Prepare("insert into extension (title,version,require_min,require_max,homepage,description,path,files,create_time,update_time) values (?,?,?,?,?,?,?,?,?,?)")
	defer stmt.Close()
	if err != nil {
		return
	}
	stmt.Exec(extension.Title, extension.Version, extension.Require.Min, extension.Require.Max, extension.Homepage, extension.Description, extension.Path, extension.Files, extension.CreateTime, extension.CreateTime)
}
