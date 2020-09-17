package hotmaze

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"strconv"
	"time"

	"cloud.google.com/go/storage"
	"github.com/google/uuid"
)

const (
	validity = 300 * time.Second
)

func (s Server) HandlerGenerateSignedURLs(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "localhost:*")
	if errCORS := s.accessControlAllowHotMaze(w, r); errCORS != nil {
		log.Println(errCORS)
		http.Error(w, errCORS.Error(), http.StatusBadRequest)
		return
	}

	if r.Method != "POST" {
		http.Error(w, "POST only", http.StatusBadRequest)
		return
	}

	ctx := context.Background()
	filesize, _ := strconv.Atoi(r.FormValue("filesize"))
	fileUUID, uploadURL, downloadURL, err := s.GenerateURLs(
		ctx,
		r.FormValue("filetype"),
		filesize,
		r.FormValue("filename"),
	)
	if err != nil {
		log.Println("generating signed URLs:", err)
		http.Error(w, "Could not generate signed URLs :(", http.StatusInternalServerError)
		return
	}

	_, err = s.ScheduleForgetFile(ctx, fileUUID)
	if err != nil {
		log.Println("scheduling file expiry:", err)
		// Better fail now, than keeping a user file forever in GCS
		http.Error(w, "Problem with file allocation :(", http.StatusInternalServerError)
		return
	}

	json.NewEncoder(w).Encode(map[string]interface{}{
		"uploadURL":   uploadURL,
		"downloadURL": downloadURL,
	})
}

func (s Server) GenerateURLs(
	ctx context.Context,
	fileType string,
	fileSize int,
	filename string,

) (fileUUID, uploadURL, downloadURL string, err error) {
	fileUUID = uuid.New().String()
	objectName := "transit/" + fileUUID
	log.Printf("Creating URLs for ephemeral resource %q\n", objectName)

	uploadURL, err = storage.SignedURL(
		s.StorageBucket,
		objectName,
		&storage.SignedURLOptions{
			GoogleAccessID: s.StorageAccountID,
			PrivateKey:     s.StoragePrivateKey,
			Method:         "PUT",
			Expires:        time.Now().Add(validity),
			ContentType:    fileType,
		})
	if err != nil {
		return
	}

	downloadURL, err = storage.SignedURL(
		s.StorageBucket,
		objectName,
		&storage.SignedURLOptions{
			GoogleAccessID: s.StorageAccountID,
			PrivateKey:     s.StoragePrivateKey,
			Method:         "GET",
			Expires:        time.Now().Add(validity),
		})

	return
}
