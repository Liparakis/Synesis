//go:build !windows

package main

import "fmt"

func main() {
	fmt.Println("Windows Explorer folder picker is supported only on Windows")
}
